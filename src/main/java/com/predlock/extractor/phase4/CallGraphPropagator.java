package com.predlock.extractor.phase4;

import com.github.javaparser.ParseProblemException;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.predlock.extractor.model.EntityModel;
import com.predlock.extractor.model.RelationshipInfo;
import com.predlock.extractor.model.ResolvedTransaction;
import com.predlock.extractor.model.SqlOperation;
import com.predlock.extractor.model.TransactionTemplate;
import com.predlock.extractor.util.ParseUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;

/**
 * Phase 4: Propagates SQL operations through the call graph into transaction boundaries.
 * <p>
 * Uses JavaParser {@link VoidVisitorAdapter} to build a call graph from {@code @Service}
 * classes, then walks the graph for each {@link TransactionTemplate} to collect all
 * {@link SqlOperation}s in execution order, including implicit SELECT/UPDATE operations
 * inferred from the {@link EntityModel}.
 */
public class CallGraphPropagator {

    private static final Logger log = LoggerFactory.getLogger(CallGraphPropagator.class);

    private static final Set<String> SERVICE_ANNOTS = Set.of(
        "Service", "Component", "Repository", "Controller", "RestController"
    );

    // Reject names that end in "Service", "Manager", "Handler" — these are
    // Spring beans, not repositories, and will never map to a table.
    private static final Set<String> NON_REPO_SUFFIXES = Set.of(
        "Service", "ServiceImpl", "Manager", "ManagerImpl",
        "Handler", "HandlerImpl", "Processor", "ProcessorImpl",
        "Helper", "Util", "Utils", "Component"
    );

    private static final Set<String> OPAQUE_METHODS = Set.of(
        "createEntityInstance", "createEntityInstanceByConcreteClass",
        "newInstance", "getInstance", "forName"
    );

    /**
     * Suffixes stripped from DAO/Repository type names when resolving to entity names.
     * Order matters: longer/more-specific suffixes must come first.
     */
    private static final List<String> DAO_TYPE_SUFFIXES = List.of(
        "RepositoryImpl", "Repository",
        "DaoImpl", "Dao",
        "EntityImpl", "Entity"
    );

    private static final Set<String> IMPLICIT_UPDATE_ENTITY_BLACKLIST = Set.of(
        "ModuleConfigurationImpl",
        "AbstractModuleConfigurationImpl",
        "AbstractModuleConfiguration"
    );

    /**
     * Propagates SQL operations into transaction boundaries by traversing the call graph.
     *
     * @param templates   the transaction templates from Phase 2
     * @param sqlMap      the method-to-SQL-operations map from Phase 3
     * @param entityModel the entity model from Phase 1
     * @param projectPath the root path of the Spring Boot project to analyze
     * @return a list of {@link ResolvedTransaction}s with fully resolved SQL operations
     */
    public List<ResolvedTransaction> propagate(List<TransactionTemplate> templates,
                                               Map<String, List<SqlOperation>> sqlMap,
                                               EntityModel entityModel,
                                               String projectPath) {
        // STEP 1: Build call graph from @Service classes
        Map<String, String> saveCallTypes = new HashMap<>();
        Map<String, String> saveFetchSources = new HashMap<>();
        Map<String, String> fieldToType = new HashMap<>();
        Set<String> newObjectVariables = new HashSet<>();
        Map<String, List<String>> callGraph = buildCallGraph(projectPath, saveCallTypes, saveFetchSources, fieldToType, newObjectVariables);

        // Build a map of method signatures to their TransactionTemplates for propagation checks
        Map<String, TransactionTemplate> templateMap = new HashMap<>();
        for (TransactionTemplate t : templates) {
            templateMap.put(t.getMethodSignature(), t);
        }

        // STEP 2 & 3: For each template, walk call graph and collect SQL ops
        List<ResolvedTransaction> results = new ArrayList<>();
        for (TransactionTemplate template : templates) {
            ResolvedTransaction resolved = new ResolvedTransaction();
            resolved.setTransactionId(template.getMethodSignature());
            resolved.setPropagation(template.getPropagation());
            resolved.setReadOnly(template.isReadOnly());

            List<SqlOperation> orderedStatements = new ArrayList<>();
            List<String> callChain = new ArrayList<>();
            Set<String> visited = new HashSet<>();

            walkCallGraph(template.getMethodSignature(), callGraph, sqlMap, entityModel,
                templateMap, template, orderedStatements, callChain, visited,
                saveCallTypes, saveFetchSources, fieldToType, newObjectVariables);

            // Deduplicate orderedStatements: same type + rawSql + tables → keep first occurrence
            List<SqlOperation> deduped = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (SqlOperation op : orderedStatements) {
                String key = op.getType() + "|" + op.getRawSql() + "|" + op.getTables();
                if (seen.add(key)) {
                    deduped.add(op);
                }
            }

            // Deduplicate callChain: keep first occurrence of each method
            List<String> dedupedChain = new ArrayList<>();
            Set<String> seenMethods = new HashSet<>();
            for (String method : callChain) {
                if (seenMethods.add(method)) {
                    dedupedChain.add(method);
                }
            }

            resolved.setOrderedStatements(deduped);
            resolved.setCallChain(dedupedChain);
            results.add(resolved);
        }

        return results;
    }

    /**
     * Overload for backward compatibility (no projectPath).
     */
    public List<ResolvedTransaction> propagate(List<TransactionTemplate> templates,
                                               Map<String, List<SqlOperation>> sqlMap,
                                               EntityModel entityModel) {
        return propagate(templates, sqlMap, entityModel, null);
    }

    private static final Set<String> SAVE_METHODS = Set.of("save", "saveAll");
    private static final Set<String> FIND_PREFIXES = Set.of(
        "find", "get", "query", "search", "read", "stream", "count", "exists");

    // -------------------------------------------------------------------------
    // STEP 1: Build call graph
    // -------------------------------------------------------------------------

    private Map<String, List<String>> buildCallGraph(String projectPath,
                                                     Map<String, String> saveCallTypes,
                                                     Map<String, String> saveFetchSources,
                                                     Map<String, String> fieldToType,
                                                     Set<String> newObjectVariables) {
        Map<String, List<String>> callGraph = new HashMap<>();
        if (projectPath == null) return callGraph;

        List<CompilationUnit> cus = ParseUtils.parseFromDir(projectPath);
        for (CompilationUnit cu : cus) {
            cu.accept(new VoidVisitorAdapter<Void>() {
                @Override
                public void visit(ClassOrInterfaceDeclaration cls, Void arg) {
                    super.visit(cls, arg);

                    boolean isServiceClass = false;
                    for (AnnotationExpr a : cls.getAnnotations()) {
                        if (SERVICE_ANNOTS.contains(a.getNameAsString())) {
                            isServiceClass = true;
                            break;
                        }
                    }
                    if (!isServiceClass) return;

                    // Build a map of field names to their types for repo detection
                    Map<String, String> fieldTypes = new HashMap<>();
                    for (FieldDeclaration field : cls.getFields()) {
                        String typeName = field.getElementType().asString();
                        for (VariableDeclarator vd : field.getVariables()) {
                            fieldTypes.put(vd.getNameAsString(), typeName);
                        }
                    }

                    String className = cls.getNameAsString();

                    // Store field-to-type mappings for resolving repository field names
                    for (Map.Entry<String, String> entry : fieldTypes.entrySet()) {
                        fieldToType.put(entry.getKey(), entry.getValue());
                    }

                    for (MethodDeclaration method : cls.getMethods()) {
                        String sig = className + "." + method.getNameAsString();

                        // Track variables created with 'new' in this method
                        if (method.getBody().isPresent()) {
                            for (VariableDeclarator vd : method.findAll(VariableDeclarator.class)) {
                                if (vd.getInitializer().isPresent()
                                    && vd.getInitializer().get() instanceof ObjectCreationExpr) {
                                    newObjectVariables.add(sig + ":" + vd.getNameAsString());
                                }
                            }
                        }

                        List<String> calls = new ArrayList<>();
                        for (MethodCallExpr mce : method.findAll(MethodCallExpr.class)) {
                            String callStr;
                            if (mce.getScope().isPresent()) {
                                String scopeName = mce.getScope().get().toString();
                                callStr = scopeName + "." + mce.getNameAsString();
                                // Also add type-resolved version if scope is a known field
                                String resolvedType = fieldTypes.get(scopeName);
                                if (resolvedType != null && !resolvedType.equals(scopeName)) {
                                    calls.add(resolvedType + "." + mce.getNameAsString());
                                }
                            } else {
                                callStr = mce.getNameAsString();
                            }
                            calls.add(callStr);
                        }
                        callGraph.put(sig, calls);

                        if (method.getBody().isPresent()) {
                            analyzeSaveCalls(method, sig, saveCallTypes, saveFetchSources, fieldTypes);
                        }
                    }
                }
            }, null);
        }
        return callGraph;
    }

    // -------------------------------------------------------------------------
    // Save call analysis
    // -------------------------------------------------------------------------

    private void analyzeSaveCalls(MethodDeclaration method, String methodSig,
                                  Map<String, String> saveCallTypes,
                                  Map<String, String> saveFetchSources,
                                  Map<String, String> fieldTypes) {
        List<Statement> statements = method.getBody().get().getStatements();
        Set<String> alreadySavedVars = new HashSet<>();

        List<MethodCallExpr> allSaveCalls = method.findAll(MethodCallExpr.class).stream()
            .filter(mce -> SAVE_METHODS.contains(mce.getNameAsString()) && mce.getScope().isPresent())
            .sorted(Comparator.comparingInt(mce -> mce.getBegin().map(p -> p.line).orElse(0)))
            .toList();

        Map<String, Integer> saveOccurrence = new HashMap<>();
        for (MethodCallExpr mce : allSaveCalls) {
            String callName = mce.getNameAsString();
            String scopeStr = mce.getScope().get().toString();
            String saveKey  = scopeStr + "." + callName;

            int occurrence = saveOccurrence.merge(saveKey, 1, Integer::sum);

            if (mce.getArguments().isEmpty()) continue;
            Expression arg = mce.getArgument(0);
            if (arg instanceof NullLiteralExpr) continue;

            String sqlType;
            String argVarName = (arg instanceof NameExpr ne) ? ne.getNameAsString() : null;

            if (argVarName != null && alreadySavedVars.contains(argVarName)) {
                sqlType = "UPDATE";
            } else {
                sqlType = determineSaveType(arg, statements, mce);
            }

            if (argVarName != null) alreadySavedVars.add(argVarName);

            String occurrenceKey = saveKey + "#" + occurrence;
            saveCallTypes.put(methodSig + ":" + occurrenceKey, sqlType);
            if (occurrence == 1) {
                saveCallTypes.put(methodSig + ":" + saveKey, sqlType);
                saveCallTypes.put(saveKey, sqlType);
            }

            String resolvedType = fieldTypes.get(scopeStr);
            if (resolvedType != null && !resolvedType.equals(scopeStr)) {
                String resolvedSaveKey = resolvedType + "." + callName;
                String resolvedOccKey  = resolvedSaveKey + "#" + occurrence;
                saveCallTypes.put(methodSig + ":" + resolvedOccKey, sqlType);
                if (occurrence == 1) {
                    saveCallTypes.put(methodSig + ":" + resolvedSaveKey, sqlType);
                    saveCallTypes.put(resolvedSaveKey, sqlType);
                }
            }

            if ("UPDATE".equals(sqlType) && arg instanceof NameExpr nameExpr) {
                String fetchSource = traceFetchSource(nameExpr.getNameAsString(), statements, mce, fieldTypes);
                if (fetchSource != null) {
                    saveFetchSources.put(methodSig + ":" + occurrenceKey, fetchSource);
                    if (occurrence == 1) {
                        saveFetchSources.put(methodSig + ":" + saveKey, fetchSource);
                        saveFetchSources.put(saveKey, fetchSource);
                    }
                    if (resolvedType != null && !resolvedType.equals(scopeStr)) {
                        String resolvedSaveKey = resolvedType + "." + callName;
                        String resolvedOccKey  = resolvedSaveKey + "#" + occurrence;
                        saveFetchSources.put(methodSig + ":" + resolvedOccKey, fetchSource);
                        if (occurrence == 1) {
                            saveFetchSources.put(methodSig + ":" + resolvedSaveKey, fetchSource);
                            saveFetchSources.put(resolvedSaveKey, fetchSource);
                        }
                    }
                }
            }
        }
    }

    private String traceFetchSource(String varName, List<Statement> statements,
                                    MethodCallExpr saveCall, Map<String, String> fieldTypes) {
        int saveLineNo = saveCall.getBegin().map(p -> p.line).orElse(Integer.MAX_VALUE);

        for (int i = statements.size() - 1; i >= 0; i--) {
            Statement stmt = statements.get(i);
            int stmtLine = stmt.getBegin().map(p -> p.line).orElse(0);
            if (stmtLine >= saveLineNo) continue;
            if (!(stmt instanceof ExpressionStmt exprStmt)) continue;

            Expression expr = exprStmt.getExpression();
            Expression initializer = null;

            if (expr instanceof VariableDeclarationExpr vde) {
                for (VariableDeclarator vd : vde.getVariables()) {
                    if (vd.getNameAsString().equals(varName) && vd.getInitializer().isPresent()) {
                        initializer = vd.getInitializer().get();
                        break;
                    }
                }
            } else if (expr instanceof AssignExpr assignExpr) {
                if (assignExpr.getTarget().toString().equals(varName)) {
                    initializer = assignExpr.getValue();
                }
            }

            if (initializer != null) return extractFetchMethodCall(initializer, fieldTypes);
        }
        return null;
    }

    private String extractFetchMethodCall(Expression init, Map<String, String> fieldTypes) {
        if (!(init instanceof MethodCallExpr mce)) return null;

        if (isFindMethod(mce.getNameAsString()) && mce.getScope().isPresent()) {
            String scope    = mce.getScope().get().toString();
            String typeName = fieldTypes.getOrDefault(scope, scope);
            return typeName + "." + mce.getNameAsString();
        }

        if (mce.getScope().isPresent() && mce.getScope().get() instanceof MethodCallExpr inner) {
            if (isFindMethod(inner.getNameAsString()) && inner.getScope().isPresent()) {
                String scope    = inner.getScope().get().toString();
                String typeName = fieldTypes.getOrDefault(scope, scope);
                return typeName + "." + inner.getNameAsString();
            }
        }
        return null;
    }

    private String determineSaveType(Expression arg, List<Statement> statements,
                                     MethodCallExpr saveCall) {
        if (arg instanceof ObjectCreationExpr)  return "INSERT";
        if (arg instanceof NameExpr nameExpr)   return traceVariableOrigin(nameExpr.getNameAsString(), statements, saveCall);
        if (arg instanceof MethodCallExpr)       return "UPDATE";
        return "UPDATE";
    }

    private String traceVariableOrigin(String varName, List<Statement> statements,
                                       MethodCallExpr saveCall) {
        int saveLineNo = saveCall.getBegin().map(p -> p.line).orElse(Integer.MAX_VALUE);

        for (int i = statements.size() - 1; i >= 0; i--) {
            Statement stmt = statements.get(i);
            int stmtLine = stmt.getBegin().map(p -> p.line).orElse(0);
            if (stmtLine >= saveLineNo) continue;
            if (!(stmt instanceof ExpressionStmt exprStmt)) continue;

            Expression expr = exprStmt.getExpression();

            if (expr instanceof VariableDeclarationExpr vde) {
                for (VariableDeclarator vd : vde.getVariables()) {
                    if (vd.getNameAsString().equals(varName) && vd.getInitializer().isPresent()) {
                        return classifyInitializer(vd.getInitializer().get());
                    }
                }
            }
            if (expr instanceof AssignExpr assignExpr) {
                if (assignExpr.getTarget().toString().equals(varName)) {
                    return classifyInitializer(assignExpr.getValue());
                }
            }
        }

        for (var param : saveCall.findAncestor(MethodDeclaration.class)
            .map(MethodDeclaration::getParameters)
            .orElse(new com.github.javaparser.ast.NodeList<>())) {
            if (param.getNameAsString().equals(varName)) return "UPDATE";
        }

        return "UPDATE";
    }

    private String classifyInitializer(Expression init) {
        if (init instanceof ObjectCreationExpr) return "INSERT";
        if (init instanceof MethodCallExpr mce) {
            if (isFindMethod(mce.getNameAsString())) return "UPDATE";
            if (mce.getScope().isPresent() && mce.getScope().get() instanceof MethodCallExpr inner) {
                if (isFindMethod(inner.getNameAsString())) return "UPDATE";
            }
        }
        return "UPDATE";
    }

    private boolean isSetterOnNewObject(String calledMethod, String callerMethodSig,
                                        Set<String> newObjectVariables) {
        int dotIdx = calledMethod.lastIndexOf('.');
        if (dotIdx < 0) return false;
        String methodName = calledMethod.substring(dotIdx + 1);
        if (!methodName.startsWith("set")) return false;
        String varName = calledMethod.substring(0, dotIdx);
        return newObjectVariables.contains(callerMethodSig + ":" + varName);
    }

    private boolean isFindMethod(String methodName) {
        for (String prefix : FIND_PREFIXES) {
            if (methodName.startsWith(prefix)) return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // STEP 2 & 3: Walk call graph
    // -------------------------------------------------------------------------

    private void walkCallGraph(String methodSig,
                               Map<String, List<String>> callGraph,
                               Map<String, List<SqlOperation>> sqlMap,
                               EntityModel entityModel,
                               Map<String, TransactionTemplate> templateMap,
                               TransactionTemplate rootTemplate,
                               List<SqlOperation> orderedStatements,
                               List<String> callChain,
                               Set<String> visited,
                               Map<String, String> saveCallTypes,
                               Map<String, String> saveFetchSources,
                               Map<String, String> fieldToType,
                               Set<String> newObjectVariables) {
        if (visited.contains(methodSig)) return;
        visited.add(methodSig);
        callChain.add(methodSig);

        List<SqlOperation> ops = sqlMap.get(methodSig);
        if (ops != null) orderedStatements.addAll(ops);

        List<String> calledMethods = callGraph.getOrDefault(methodSig, Collections.emptyList());
        if (calledMethods.isEmpty()) {
            TransactionTemplate t = templateMap.get(methodSig);
            if (t != null && t.getCalledMethods() != null) {
                calledMethods = t.getCalledMethods();
            }
        }

        Set<String> typeNames = new HashSet<>(fieldToType.values());
        Map<String, Integer> walkSaveOccurrence = new HashMap<>();

        for (String calledMethod : calledMethods) {
            int dotIdx = calledMethod.lastIndexOf('.');
            if (dotIdx > 0 && SAVE_METHODS.contains(calledMethod.substring(dotIdx + 1))) {
                String scopePart = calledMethod.substring(0, dotIdx);
                if (!typeNames.contains(scopePart)) {
                    walkSaveOccurrence.merge(calledMethod, 1, Integer::sum);
                }
            }

            TransactionTemplate calledTemplate = templateMap.get(calledMethod);
            if (calledTemplate != null && "REQUIRES_NEW".equals(calledTemplate.getPropagation())) {
                continue;
            }

            if (!isSetterOnNewObject(calledMethod, methodSig, newObjectVariables)) {
                inferImplicitSql(calledMethod, entityModel, rootTemplate, orderedStatements);
            }

            List<SqlOperation> calledOps = sqlMap.get(calledMethod);
            if (calledOps == null) {
                int dot = calledMethod.indexOf('.');
                if (dot > 0) {
                    String fieldName = calledMethod.substring(0, dot);
                    String typeName  = fieldToType.get(fieldName);
                    if (typeName != null) {
                        String resolvedKey = typeName + calledMethod.substring(dot);
                        calledOps = sqlMap.get(resolvedKey);
                    }
                }
            }
            if (calledOps != null) {
                orderedStatements.addAll(calledOps);
                if (!callChain.contains(calledMethod)) callChain.add(calledMethod);
            }

            {
                int sDot   = calledMethod.lastIndexOf('.');
                String sScope  = sDot > 0 ? calledMethod.substring(0, sDot) : "";
                String sMethod = sDot > 0 ? calledMethod.substring(sDot + 1) : calledMethod;
                if (!(SAVE_METHODS.contains(sMethod) && typeNames.contains(sScope))) {
                    int saveOcc = walkSaveOccurrence.getOrDefault(calledMethod, 0);
                    inferSaveType(calledMethod, methodSig, saveCallTypes, saveFetchSources,
                        entityModel, orderedStatements, sqlMap, fieldToType, saveOcc);
                }
            }

            if (callGraph.containsKey(calledMethod) && !visited.contains(calledMethod)) {
                int oDot = calledMethod.lastIndexOf('.');
                String oMethod = oDot > 0 ? calledMethod.substring(oDot + 1) : calledMethod;
                if (OPAQUE_METHODS.contains(oMethod)) continue;  // don't recurse into factories
                walkCallGraph(calledMethod, callGraph, sqlMap, entityModel,
                    templateMap, rootTemplate, orderedStatements, callChain, visited,
                    saveCallTypes, saveFetchSources, fieldToType, newObjectVariables);
            }
        }
    }

    // -------------------------------------------------------------------------
    // inferSaveType
    // -------------------------------------------------------------------------

    private void inferSaveType(String calledMethod, String callerMethodSig,
                               Map<String, String> saveCallTypes,
                               Map<String, String> saveFetchSources,
                               EntityModel entityModel,
                               List<SqlOperation> orderedStatements,
                               Map<String, List<SqlOperation>> sqlMap,
                               Map<String, String> fieldToType,
                               int occurrence) {
        int dotIdx = calledMethod.lastIndexOf('.');
        if (dotIdx < 0) return;
        String methodName = calledMethod.substring(dotIdx + 1);
        if (!SAVE_METHODS.contains(methodName)) return;

        String occKey = calledMethod + "#" + occurrence;
        String type = saveCallTypes.get(callerMethodSig + ":" + occKey);
        if (type == null) type = saveCallTypes.get(callerMethodSig + ":" + calledMethod);
        if (type == null) type = saveCallTypes.get(calledMethod);
        if (type == null) return;

        if ("UPDATE".equals(type)) {
            String fetchSource = saveFetchSources.get(callerMethodSig + ":" + occKey);
            if (fetchSource == null) fetchSource = saveFetchSources.get(callerMethodSig + ":" + calledMethod);
            if (fetchSource == null) fetchSource = saveFetchSources.get(calledMethod);
            if (fetchSource != null) {
                List<SqlOperation> fetchOps = sqlMap.get(fetchSource);
                if (fetchOps == null) {
                    int fDot = fetchSource.indexOf('.');
                    if (fDot > 0) {
                        String fType = fieldToType.get(fetchSource.substring(0, fDot));
                        if (fType != null) fetchOps = sqlMap.get(fType + fetchSource.substring(fDot));
                    }
                }
                if (fetchOps != null) {
                    orderedStatements.addAll(0, fetchOps);
                } else {
                    String repoScope = calledMethod.substring(0, dotIdx);
                    String tableName = inferTableFromRepo(repoScope, entityModel, fieldToType);
                    if (tableName != null) {
                        orderedStatements.add(0, new SqlOperation(
                            "SELECT",
                            "SELECT * FROM " + tableName + " WHERE id = ?",
                            List.of(tableName), "id = ?", fetchSource));
                    }
                }
            }
        }

        boolean foundMatchingOp = false;
        for (SqlOperation op : orderedStatements) {
            if (calledMethod.equals(op.getSourceMethod()) && type.equals(op.getType())) {
                foundMatchingOp = true;
                break;
            }
        }

        for (SqlOperation op : orderedStatements) {
            if (calledMethod.equals(op.getSourceMethod())
                && !type.equals(op.getType())
                && "UPDATE".equals(op.getType())) {
                op.setType(type);
                if ("INSERT".equals(type) && op.getRawSql() != null
                    && op.getRawSql().startsWith("UPDATE")) {
                    String table = op.getTables() != null && !op.getTables().isEmpty()
                        ? op.getTables().get(0) : "unknown";
                    op.setRawSql("INSERT INTO " + table + " VALUES (?)");
                    op.setWhereCondition(null);
                }
                foundMatchingOp = true;
            }
        }

        if (!foundMatchingOp && "UPDATE".equals(type)) {
            String repoScope = calledMethod.substring(0, dotIdx);
            String tableName = inferTableFromRepo(repoScope, entityModel, fieldToType);
            if (tableName != null) {
                boolean hasSetterUpdates = orderedStatements.stream().anyMatch(op ->
                    "UPDATE".equals(op.getType())
                        && op.getTables() != null && op.getTables().contains(tableName)
                        && op.getSourceMethod() != null && op.getSourceMethod().contains(".set"));
                if (hasSetterUpdates) foundMatchingOp = true;
            }
        }

        if (!foundMatchingOp) {
            String repoScope = calledMethod.substring(0, dotIdx);
            String tableName = inferTableFromRepo(repoScope, entityModel, fieldToType);
            if (tableName != null) {
                if ("INSERT".equals(type)) {
                    orderedStatements.add(new SqlOperation(
                        "INSERT", "INSERT INTO " + tableName + " VALUES (?)",
                        List.of(tableName), null, calledMethod));
                } else if ("UPDATE".equals(type)) {
                    String setClause = buildSetClauseFromSetters(orderedStatements);
                    orderedStatements.add(new SqlOperation(
                        "UPDATE", "UPDATE " + tableName + " SET " + setClause + " WHERE id = ?",
                        List.of(tableName), "id = ?", calledMethod));
                }
            }
        }
    }

    private String buildSetClauseFromSetters(List<SqlOperation> orderedStatements) {
        List<String> columns = new ArrayList<>();
        for (SqlOperation op : orderedStatements) {
            if ("UPDATE".equals(op.getType())
                && op.getSourceMethod() != null
                && op.getSourceMethod().contains(".set")) {
                String raw = op.getRawSql();
                if (raw != null) {
                    int setIdx = raw.indexOf("SET ");
                    int eqIdx  = raw.indexOf(" = ?", setIdx);
                    if (setIdx >= 0 && eqIdx > setIdx) {
                        String col = raw.substring(setIdx + 4, eqIdx).trim();
                        if (!col.isEmpty() && !columns.contains(col)) columns.add(col);
                    }
                }
            }
        }
        if (columns.isEmpty()) return "...";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(columns.get(i)).append(" = ?");
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // inferTableFromRepo — FIXED to handle Dao/DaoImpl/RepositoryImpl suffixes
    // -------------------------------------------------------------------------

    /**
     * Infers the database table name from a repository field or type name.
     * <p>Resolution order:
     * <ol>
     *   <li>Resolve the field name to its declared type via {@code fieldToType}
     *       (e.g. {@code "countryDao"} → {@code "CountryDao"})</li>
     *   <li>Strip known suffixes — {@code RepositoryImpl}, {@code Repository},
     *       {@code DaoImpl}, {@code Dao}, {@code EntityImpl}, {@code Entity} —
     *       to obtain the base entity name (e.g. {@code "CountryDao"} → {@code "Country"})</li>
     *   <li>Direct lookup in entity model (base name may itself be an entity key)</li>
     *   <li>Try {@code base + "Impl"} — the most common JPA pattern in frameworks
     *       like Broadleaf (e.g. {@code "Country"} → {@code "CountryImpl"} → {@code BLC_COUNTRY})</li>
     *   <li>Fall back to {@code camelToSnake(base)} — produces {@code "country"} instead
     *       of the incorrect {@code "country_dao"}</li>
     * </ol>
     */
    private String inferTableFromRepo(String repoName, EntityModel entityModel,
                                      Map<String, String> fieldToType) {
        // 1. Resolve field name → declared type (e.g. "countryDao" → "CountryDao")
        String typeName = fieldToType.getOrDefault(repoName, repoName);

        // Guard: non-DAO beans should not generate table names
        for (String suffix : NON_REPO_SUFFIXES) {
            if (typeName.endsWith(suffix)) {
                log.debug("Skipping non-repo type '{}' in inferTableFromRepo", typeName);
                return null;
            }
        }

        // 2. Strip known suffixes to get base entity name
        String base = typeName;
        for (String suffix : DAO_TYPE_SUFFIXES) {
            if (typeName.endsWith(suffix) && typeName.length() > suffix.length()) {
                base = typeName.substring(0, typeName.length() - suffix.length());
                break;
            }
        }

        // 3. Direct match in entity model (base may already be an entity key)
        String table = entityModel.getEntityToTable().get(base);
        if (table != null) return table;

        // 4. Try base + "Impl" (most common JPA / Broadleaf pattern)
        table = entityModel.getEntityToTable().get(base + "Impl");
        if (table != null) return table;

        // 5. If no suffix was stripped and no direct match, nothing more to try
        if (base.equals(typeName)) return camelToSnake(base);

        // 6. camelToSnake fallback on the stripped base — produces "country" not "country_dao"
        return camelToSnake(base);
    }

    // -------------------------------------------------------------------------
    // STEP 3: Infer implicit SQL from getter/setter calls
    // -------------------------------------------------------------------------

    private void inferImplicitSql(String calledMethod, EntityModel entityModel,
                                  TransactionTemplate rootTemplate,
                                  List<SqlOperation> orderedStatements) {
        int dotIdx = calledMethod.lastIndexOf('.');
        if (dotIdx < 0) return;

        String methodName = calledMethod.substring(dotIdx + 1);

        // IMPLICIT SELECT: entity.getXxx() where xxx is a LAZY relationship
        if (methodName.startsWith("get") && methodName.length() > 3) {
            String fieldName = Character.toLowerCase(methodName.charAt(3)) + methodName.substring(4);

            for (Map.Entry<String, RelationshipInfo> entry : entityModel.getRelationships().entrySet()) {
                String key = entry.getKey();
                RelationshipInfo rel = entry.getValue();

                if (key.endsWith("." + fieldName) && "LAZY".equals(rel.getFetchType())) {
                    String targetEntity = rel.getTargetEntity();
                    String tableName = entityModel.getEntityToTable().getOrDefault(
                        targetEntity,
                        targetEntity != null ? targetEntity.toLowerCase() : "unknown");
                    String joinCol = rel.getJoinColumn() != null ? rel.getJoinColumn() : "id";

                    orderedStatements.add(new SqlOperation(
                        "SELECT",
                        "SELECT * FROM " + tableName + " WHERE " + joinCol + " = ?",
                        List.of(tableName), joinCol + " = ?", calledMethod));
                    break;
                }
            }
        }

        // IMPLICIT UPDATE: entity.setXxx() in a non-readOnly transaction
        if (methodName.startsWith("set") && methodName.length() > 3 && !rootTemplate.isReadOnly()) {
            String fieldName = Character.toLowerCase(methodName.charAt(3)) + methodName.substring(4);

            for (Map.Entry<String, String> entry : entityModel.getFieldToColumn().entrySet()) {
                String key        = entry.getKey();
                String columnName = entry.getValue();

                if (key.endsWith("." + fieldName)) {
                    String entityClassName = key.substring(0, key.indexOf('.'));
                    String tableName = entityModel.getEntityToTable().getOrDefault(
                        entityClassName, entityClassName.toLowerCase());

                    if (IMPLICIT_UPDATE_ENTITY_BLACKLIST.contains(entityClassName)) {
                        log.debug("Skipping implicit UPDATE for blacklisted abstract entity '{}'", entityClassName);
                        continue;
                    }

                    orderedStatements.add(new SqlOperation(
                        "UPDATE",
                        "UPDATE " + tableName + " SET " + columnName + " = ? WHERE id = ?",
                        List.of(tableName), "id = ?", calledMethod));
                    break;
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    private static String camelToSnake(String camel) {
        if (camel == null || camel.isEmpty()) return camel;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) sb.append('_');
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}