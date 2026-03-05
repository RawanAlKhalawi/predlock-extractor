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
                    templateMap, template, orderedStatements, callChain, visited, saveCallTypes, saveFetchSources, fieldToType, newObjectVariables);

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
    private static final Set<String> FIND_PREFIXES = Set.of("find", "get", "query", "search", "read", "stream", "count", "exists");

    /**
     * STEP 1: Build call graph by parsing @Service classes and recording method call edges.
     * Also populates saveCallTypes with INSERT/UPDATE classification for save/saveAll calls.
     */
    private Map<String, List<String>> buildCallGraph(String projectPath,
                                                     Map<String, String> saveCallTypes,
                                                     Map<String, String> saveFetchSources,
                                                     Map<String, String> fieldToType,
                                                     Set<String> newObjectVariables) {
        Map<String, List<String>> callGraph = new HashMap<>();
        if (projectPath == null) {
            return callGraph;
        }

        List<CompilationUnit> cus = parseFromDir(projectPath);
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
                        // Track variables created with 'new' in this method (search recursively to find inside loops)
                        if (method.getBody().isPresent()) {
                            for (VariableDeclarator vd : method.findAll(VariableDeclarator.class)) {
                                if (vd.getInitializer().isPresent() && vd.getInitializer().get() instanceof ObjectCreationExpr) {
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

                        // Analyze save/saveAll calls for INSERT vs UPDATE
                        if (method.getBody().isPresent()) {
                            analyzeSaveCalls(method, sig, saveCallTypes, saveFetchSources, fieldTypes);
                        }
                    }
                }
            }, null);
        }
        return callGraph;
    }

    /**
     * Analyzes save() and saveAll() calls in a method body to determine if they
     * represent INSERT (new entity) or UPDATE (fetched entity) operations.
     */
    private void analyzeSaveCalls(MethodDeclaration method, String methodSig,
                                  Map<String, String> saveCallTypes,
                                  Map<String, String> saveFetchSources,
                                  Map<String, String> fieldTypes) {
        List<Statement> statements = method.getBody().get().getStatements();

        // Track which variables have already been saved (for detecting second save = UPDATE)
        Set<String> alreadySavedVars = new HashSet<>();

        // Process save calls in source order
        List<MethodCallExpr> allSaveCalls = method.findAll(MethodCallExpr.class).stream()
                .filter(mce -> SAVE_METHODS.contains(mce.getNameAsString()) && mce.getScope().isPresent())
                .sorted(Comparator.comparingInt(mce -> mce.getBegin().map(p -> p.line).orElse(0)))
                .toList();

        Map<String, Integer> saveOccurrence = new HashMap<>();
        for (MethodCallExpr mce : allSaveCalls) {
            String callName = mce.getNameAsString();
            String scopeStr = mce.getScope().get().toString();
            String saveKey = scopeStr + "." + callName;

            // Track occurrence number for this save key
            int occurrence = saveOccurrence.merge(saveKey, 1, Integer::sum);

            // Get the argument passed to save/saveAll
            if (mce.getArguments().isEmpty()) continue;
            Expression arg = mce.getArgument(0);

            // Skip null literal arguments — can't determine type
            if (arg instanceof NullLiteralExpr) continue;

            String sqlType;
            String argVarName = (arg instanceof NameExpr ne) ? ne.getNameAsString() : null;

            // If this variable was already saved earlier in this method, it's UPDATE
            if (argVarName != null && alreadySavedVars.contains(argVarName)) {
                sqlType = "UPDATE";
            } else {
                sqlType = determineSaveType(arg, statements, mce);
            }

            // Record that this variable has been saved
            if (argVarName != null) {
                alreadySavedVars.add(argVarName);
            }

            // Use occurrence-based key for multiple saves with same key
            String occurrenceKey = saveKey + "#" + occurrence;
            saveCallTypes.put(methodSig + ":" + occurrenceKey, sqlType);
            // Also store without occurrence for single-save cases
            if (occurrence == 1) {
                saveCallTypes.put(methodSig + ":" + saveKey, sqlType);
                saveCallTypes.put(saveKey, sqlType);
            }

            // Also store type-resolved keys for the save call
            String resolvedType = fieldTypes.get(scopeStr);
            if (resolvedType != null && !resolvedType.equals(scopeStr)) {
                String resolvedSaveKey = resolvedType + "." + callName;
                String resolvedOccKey = resolvedSaveKey + "#" + occurrence;
                saveCallTypes.put(methodSig + ":" + resolvedOccKey, sqlType);
                if (occurrence == 1) {
                    saveCallTypes.put(methodSig + ":" + resolvedSaveKey, sqlType);
                    saveCallTypes.put(resolvedSaveKey, sqlType);
                }
            }

            // If UPDATE, find the fetch source method for the entity variable
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
                        String resolvedOccKey = resolvedSaveKey + "#" + occurrence;
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

    /**
     * Traces backward to find the repository fetch method that assigned a variable.
     * Returns the fetch method call string (e.g., "ProductRepository.findById") or null.
     */
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

            if (initializer != null) {
                return extractFetchMethodCall(initializer, fieldTypes);
            }
        }
        return null;
    }

    /**
     * Extracts the repository fetch method call from an initializer expression.
     * Handles patterns like repo.findById(...), repo.findById(...).orElseThrow(...), etc.
     * Returns type-resolved method key (e.g., "ProductRepository.findById") or null.
     */
    private String extractFetchMethodCall(Expression init, Map<String, String> fieldTypes) {
        if (!(init instanceof MethodCallExpr mce)) return null;

        // Direct: repo.findById(...)
        if (isFindMethod(mce.getNameAsString()) && mce.getScope().isPresent()) {
            String scope = mce.getScope().get().toString();
            String typeName = fieldTypes.getOrDefault(scope, scope);
            return typeName + "." + mce.getNameAsString();
        }

        // Chained: repo.findById(...).orElseThrow(...) or .get()
        if (mce.getScope().isPresent() && mce.getScope().get() instanceof MethodCallExpr inner) {
            if (isFindMethod(inner.getNameAsString()) && inner.getScope().isPresent()) {
                String scope = inner.getScope().get().toString();
                String typeName = fieldTypes.getOrDefault(scope, scope);
                return typeName + "." + inner.getNameAsString();
            }
        }

        return null;
    }

    /**
     * Determines whether a save/saveAll call should be INSERT or UPDATE by
     * looking backward in the method body for how the entity variable was assigned.
     */
    private String determineSaveType(Expression arg, List<Statement> statements,
                                     MethodCallExpr saveCall) {
        // If the argument is directly "new ClassName(...)" → INSERT
        if (arg instanceof ObjectCreationExpr) {
            return "INSERT";
        }

        // If the argument is a variable name, trace it backward
        if (arg instanceof NameExpr nameExpr) {
            String varName = nameExpr.getNameAsString();
            return traceVariableOrigin(varName, statements, saveCall);
        }

        // If the argument is a method call (e.g., repo.findById(...).get()) → UPDATE
        if (arg instanceof MethodCallExpr) {
            return "UPDATE";
        }

        // Conservative default: UPDATE
        return "UPDATE";
    }

    /**
     * Traces a variable backward through method statements to determine if it
     * was created with "new" (INSERT) or fetched from a repository (UPDATE).
     */
    private String traceVariableOrigin(String varName, List<Statement> statements,
                                       MethodCallExpr saveCall) {
        // Search backward from the save call position
        int saveLineNo = saveCall.getBegin().map(p -> p.line).orElse(Integer.MAX_VALUE);

        for (int i = statements.size() - 1; i >= 0; i--) {
            Statement stmt = statements.get(i);
            int stmtLine = stmt.getBegin().map(p -> p.line).orElse(0);
            if (stmtLine >= saveLineNo) continue;

            if (!(stmt instanceof ExpressionStmt exprStmt)) continue;
            Expression expr = exprStmt.getExpression();

            // Check variable declaration: Type var = ...
            if (expr instanceof VariableDeclarationExpr vde) {
                for (VariableDeclarator vd : vde.getVariables()) {
                    if (vd.getNameAsString().equals(varName) && vd.getInitializer().isPresent()) {
                        return classifyInitializer(vd.getInitializer().get());
                    }
                }
            }

            // Check assignment: var = ...
            if (expr instanceof AssignExpr assignExpr) {
                if (assignExpr.getTarget().toString().equals(varName)) {
                    return classifyInitializer(assignExpr.getValue());
                }
            }
        }

        // Also check method parameters — conservative: assume UPDATE
        for (var param : saveCall.findAncestor(MethodDeclaration.class)
                .map(MethodDeclaration::getParameters).orElse(new com.github.javaparser.ast.NodeList<>())) {
            if (param.getNameAsString().equals(varName)) {
                return "UPDATE";
            }
        }

        // Default: UPDATE (conservative)
        return "UPDATE";
    }

    /**
     * Classifies an initializer expression as INSERT (new object) or UPDATE (fetched).
     */
    private String classifyInitializer(Expression init) {
        // new ClassName(...) → INSERT
        if (init instanceof ObjectCreationExpr) {
            return "INSERT";
        }
        // repo.findXxx(...) or repo.findXxx(...).get() → UPDATE
        if (init instanceof MethodCallExpr mce) {
            String name = mce.getNameAsString();
            // Check if it's a find/get method or .get()/.orElse() on a find result
            if (isFindMethod(name)) {
                return "UPDATE";
            }
            // Check for chained calls like findById(...).get()
            if (mce.getScope().isPresent() && mce.getScope().get() instanceof MethodCallExpr inner) {
                if (isFindMethod(inner.getNameAsString())) {
                    return "UPDATE";
                }
            }
        }
        return "UPDATE";
    }

    /**
     * Checks if a setter call is on a variable that was created with 'new' in the same method.
     * If so, the setter should NOT generate implicit SQL.
     */
    private boolean isSetterOnNewObject(String calledMethod, String callerMethodSig, Set<String> newObjectVariables) {
        int dotIdx = calledMethod.lastIndexOf('.');
        if (dotIdx < 0) return false;
        String methodName = calledMethod.substring(dotIdx + 1);
        if (!methodName.startsWith("set")) return false;
        String varName = calledMethod.substring(0, dotIdx);
        return newObjectVariables.contains(callerMethodSig + ":" + varName);
    }

    /**
     * Checks if a method name looks like a repository find/fetch method.
     */
    private boolean isFindMethod(String methodName) {
        for (String prefix : FIND_PREFIXES) {
            if (methodName.startsWith(prefix)) return true;
        }
        return false;
    }

    /**
     * STEP 2 & 3: Recursively walk the call graph from a method, collecting SQL operations
     * and inferring implicit SQL from entity model.
     */
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

        // Collect SQL operations from sqlMap for this method
        List<SqlOperation> ops = sqlMap.get(methodSig);
        if (ops != null) {
            orderedStatements.addAll(ops);
        }

        // Get called methods from call graph or from template's calledMethods
        List<String> calledMethods = callGraph.getOrDefault(methodSig, Collections.emptyList());
        if (calledMethods.isEmpty()) {
            TransactionTemplate t = templateMap.get(methodSig);
            if (t != null && t.getCalledMethods() != null) {
                calledMethods = t.getCalledMethods();
            }
        }

        // Build a set of type names that are values in fieldToType (to detect type-resolved entries)
        Set<String> typeNames = new HashSet<>(fieldToType.values());

        Map<String, Integer> walkSaveOccurrence = new HashMap<>();
        for (String calledMethod : calledMethods) {
            int dotIdx = calledMethod.lastIndexOf('.');
            if (dotIdx > 0 && SAVE_METHODS.contains(calledMethod.substring(dotIdx + 1))) {
                String scopePart = calledMethod.substring(0, dotIdx);
                // Only count field-name versions, skip type-resolved duplicates
                if (!typeNames.contains(scopePart)) {
                    walkSaveOccurrence.merge(calledMethod, 1, Integer::sum);
                }
            }

            // Check if called method is itself a @Transactional method
            TransactionTemplate calledTemplate = templateMap.get(calledMethod);

            if (calledTemplate != null) {
                // Apply propagation rules
                String calledPropagation = calledTemplate.getPropagation();

                if ("REQUIRES_NEW".equals(calledPropagation)) {
                    // Different transaction scope — skip
                    continue;
                }

                // Check self-invocation: if the root template has selfInvocation=true
                // for this call, always merge (proxy bypassed)
                // REQUIRED or selfInvocation → merge into current scope
            }

            // STEP 3: Infer implicit SQL from entity model
            // BUG 3 fix: skip implicit UPDATE for setters on new objects
            if (!isSetterOnNewObject(calledMethod, methodSig, newObjectVariables)) {
                inferImplicitSql(calledMethod, entityModel, rootTemplate, orderedStatements);
            }

            // Collect SQL from the called method
            // Try direct lookup first, then try type-resolved lookup
            List<SqlOperation> calledOps = sqlMap.get(calledMethod);
            if (calledOps == null) {
                // Try resolving field name to type name
                int dot = calledMethod.indexOf('.');
                if (dot > 0) {
                    String fieldName = calledMethod.substring(0, dot);
                    String typeName = fieldToType.get(fieldName);
                    if (typeName != null) {
                        String resolvedKey = typeName + calledMethod.substring(dot);
                        calledOps = sqlMap.get(resolvedKey);
                    }
                }
            }
            if (calledOps != null) {
                orderedStatements.addAll(calledOps);
                if (!callChain.contains(calledMethod)) {
                    callChain.add(calledMethod);
                }
            }

            // Infer save/saveAll type from backward analysis (must be after ops are added)
            // Skip type-resolved save calls to avoid duplicate generation
            {
                int sDot = calledMethod.lastIndexOf('.');
                String sScope = sDot > 0 ? calledMethod.substring(0, sDot) : "";
                String sMethod = sDot > 0 ? calledMethod.substring(sDot + 1) : calledMethod;
                if (!(SAVE_METHODS.contains(sMethod) && typeNames.contains(sScope))) {
                    int saveOcc = walkSaveOccurrence.getOrDefault(calledMethod, 0);
                    inferSaveType(calledMethod, methodSig, saveCallTypes, saveFetchSources, entityModel, orderedStatements, sqlMap, fieldToType, saveOcc);
                }
            }

            // Recurse into call graph
            if (callGraph.containsKey(calledMethod) && !visited.contains(calledMethod)) {
                walkCallGraph(calledMethod, callGraph, sqlMap, entityModel,
                        templateMap, rootTemplate, orderedStatements, callChain, visited, saveCallTypes, saveFetchSources, fieldToType, newObjectVariables);
            }
        }
    }

    /**
     * Infers the SQL operation type for save/saveAll calls based on backward analysis.
     * If the save call was analyzed during call graph building, uses that classification
     * to update existing SQL operations or generate new ones.
     */
    private void inferSaveType(String calledMethod, String callerMethodSig,
                               Map<String, String> saveCallTypes,
                               Map<String, String> saveFetchSources,
                               EntityModel entityModel,
                               List<SqlOperation> orderedStatements,
                               Map<String, List<SqlOperation>> sqlMap,
                               Map<String, String> fieldToType,
                               int occurrence) {
        // Check if this is a save/saveAll call
        int dotIdx = calledMethod.lastIndexOf('.');
        if (dotIdx < 0) return;
        String methodName = calledMethod.substring(dotIdx + 1);
        if (!SAVE_METHODS.contains(methodName)) return;

        // Look up the determined type from backward analysis (try occurrence-based key first)
        String occKey = calledMethod + "#" + occurrence;
        String type = saveCallTypes.get(callerMethodSig + ":" + occKey);
        if (type == null) {
            type = saveCallTypes.get(callerMethodSig + ":" + calledMethod);
        }
        if (type == null) {
            type = saveCallTypes.get(calledMethod);
        }
        if (type == null) return;

        // If UPDATE and there's a fetch source, insert the SELECT before the UPDATE
        if ("UPDATE".equals(type)) {
            String fetchSource = saveFetchSources.get(callerMethodSig + ":" + occKey);
            if (fetchSource == null) {
                fetchSource = saveFetchSources.get(callerMethodSig + ":" + calledMethod);
            }
            if (fetchSource == null) {
                fetchSource = saveFetchSources.get(calledMethod);
            }
            if (fetchSource != null) {
                // Try to find the SELECT op from sqlMap
                List<SqlOperation> fetchOps = sqlMap.get(fetchSource);
                if (fetchOps == null) {
                    // Try field-name based lookup
                    int fDot = fetchSource.indexOf('.');
                    if (fDot > 0) {
                        String fField = fetchSource.substring(0, fDot);
                        String fType = fieldToType.get(fField);
                        if (fType != null) {
                            fetchOps = sqlMap.get(fType + fetchSource.substring(fDot));
                        }
                    }
                }
                if (fetchOps != null) {
                    // BUG 1 fix: insert SELECT at beginning, not end
                    orderedStatements.addAll(0, fetchOps);
                } else {
                    // Construct a SELECT operation from entity model
                    String repoScope = calledMethod.substring(0, dotIdx);
                    String tableName = inferTableFromRepo(repoScope, entityModel, fieldToType);
                    if (tableName != null) {
                        SqlOperation selectOp = new SqlOperation(
                                "SELECT",
                                "SELECT * FROM " + tableName + " WHERE id = ?",
                                List.of(tableName),
                                "id = ?",
                                fetchSource
                        );
                        // BUG 1 fix: insert SELECT at beginning, not end
                        orderedStatements.add(0, selectOp);
                    }
                }
            }
        }

        // Check if there's already an op from sqlMap for this exact save call with matching type
        boolean foundMatchingOp = false;
        for (SqlOperation op : orderedStatements) {
            if (calledMethod.equals(op.getSourceMethod()) && type.equals(op.getType())) {
                foundMatchingOp = true;
                break;
            }
        }

        // Update any existing ops that have wrong type (UPDATE→INSERT for new objects)
        for (SqlOperation op : orderedStatements) {
            if (calledMethod.equals(op.getSourceMethod()) && !type.equals(op.getType()) && "UPDATE".equals(op.getType())) {
                op.setType(type);
                if ("INSERT".equals(type) && op.getRawSql() != null && op.getRawSql().startsWith("UPDATE")) {
                    String table = op.getTables() != null && !op.getTables().isEmpty()
                            ? op.getTables().get(0) : "unknown";
                    op.setRawSql("INSERT INTO " + table + " VALUES (?)");
                    op.setWhereCondition(null);
                }
                foundMatchingOp = true;
            }
        }

        // BUG 2 fix: If UPDATE and setter-inferred UPDATEs already exist for the same table,
        // skip generating a generic UPDATE — the setter UPDATEs already represent this save().
        if (!foundMatchingOp && "UPDATE".equals(type)) {
            String repoScope = calledMethod.substring(0, dotIdx);
            String tableName = inferTableFromRepo(repoScope, entityModel, fieldToType);
            if (tableName != null) {
                boolean hasSetterUpdates = false;
                for (SqlOperation op : orderedStatements) {
                    if ("UPDATE".equals(op.getType()) && op.getTables() != null
                            && op.getTables().contains(tableName)
                            && op.getSourceMethod() != null
                            && op.getSourceMethod().contains(".set")) {
                        hasSetterUpdates = true;
                        break;
                    }
                }
                if (hasSetterUpdates) {
                    foundMatchingOp = true; // skip generating generic UPDATE
                }
            }
        }

        // If no matching op exists, generate one
        if (!foundMatchingOp) {
            String repoScope = calledMethod.substring(0, dotIdx);
            String tableName = inferTableFromRepo(repoScope, entityModel, fieldToType);
            if (tableName != null) {
                if ("INSERT".equals(type)) {
                    orderedStatements.add(new SqlOperation(
                            "INSERT", "INSERT INTO " + tableName + " VALUES (?)",
                            List.of(tableName), null, calledMethod));
                } else if ("UPDATE".equals(type)) {
                    // BUG 3 fix: build SET clause from preceding setter calls on the same variable
                    String setClause = buildSetClauseFromSetters(calledMethod, orderedStatements, entityModel);
                    orderedStatements.add(new SqlOperation(
                            "UPDATE", "UPDATE " + tableName + " SET " + setClause + " WHERE id = ?",
                            List.of(tableName), "id = ?", calledMethod));
                }
            }
        }
    }

    /**
     * Builds a SET clause from setter-inferred UPDATEs that precede a save() call on the same table.
     * Falls back to "..." if no setters are found.
     */
    private String buildSetClauseFromSetters(String saveMethod, List<SqlOperation> orderedStatements, EntityModel entityModel) {
        // Infer table from the save method's repo
        int dotIdx = saveMethod.lastIndexOf('.');
        String repoScope = dotIdx > 0 ? saveMethod.substring(0, dotIdx) : "";
        // Collect column names from setter-inferred UPDATEs
        List<String> columns = new ArrayList<>();
        for (SqlOperation op : orderedStatements) {
            if ("UPDATE".equals(op.getType()) && op.getSourceMethod() != null && op.getSourceMethod().contains(".set")) {
                // Extract column from rawSql: "UPDATE table SET column = ? WHERE id = ?"
                String raw = op.getRawSql();
                if (raw != null) {
                    int setIdx = raw.indexOf("SET ");
                    int eqIdx = raw.indexOf(" = ?", setIdx);
                    if (setIdx >= 0 && eqIdx > setIdx) {
                        String col = raw.substring(setIdx + 4, eqIdx).trim();
                        if (!col.isEmpty() && !columns.contains(col)) {
                            columns.add(col);
                        }
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

    /**
     * Infers the table name from a repository field/type name using entity model.
     */
    private String inferTableFromRepo(String repoName, EntityModel entityModel, Map<String, String> fieldToType) {
        // Resolve field name to type if needed
        String typeName = fieldToType.getOrDefault(repoName, repoName);
        // Strip "Repository" suffix to get entity name
        String entityName = typeName.replaceAll("Repository$", "");
        String table = entityModel.getEntityToTable().get(entityName);
        if (table != null) return table;
        // Try camelToSnake fallback
        if (!entityName.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < entityName.length(); i++) {
                char c = entityName.charAt(i);
                if (Character.isUpperCase(c)) {
                    if (i > 0) sb.append('_');
                    sb.append(Character.toLowerCase(c));
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }
        return null;
    }

    /**
     * STEP 3: Infer implicit SQL operations from entity getter/setter calls.
     */
    private void inferImplicitSql(String calledMethod, EntityModel entityModel,
                                  TransactionTemplate rootTemplate,
                                  List<SqlOperation> orderedStatements) {
        // Parse method call: "entity.getXxx()" or "entity.setXxx()"
        int dotIdx = calledMethod.lastIndexOf('.');
        if (dotIdx < 0) return;

        String methodName = calledMethod.substring(dotIdx + 1);

        // IMPLICIT SELECT: entity.getXxx() where xxx is a LAZY relationship
        if (methodName.startsWith("get") && methodName.length() > 3) {
            String fieldName = Character.toLowerCase(methodName.charAt(3)) + methodName.substring(4);

            // Check all entity classes for this field in relationships
            for (Map.Entry<String, RelationshipInfo> entry : entityModel.getRelationships().entrySet()) {
                String key = entry.getKey(); // "ClassName.fieldName"
                RelationshipInfo rel = entry.getValue();

                if (key.endsWith("." + fieldName) && "LAZY".equals(rel.getFetchType())) {
                    String targetEntity = rel.getTargetEntity();
                    String tableName = entityModel.getEntityToTable().getOrDefault(targetEntity,
                            targetEntity != null ? targetEntity.toLowerCase() : "unknown");
                    String joinCol = rel.getJoinColumn() != null ? rel.getJoinColumn() : "id";

                    SqlOperation implicitSelect = new SqlOperation(
                            "SELECT",
                            "SELECT * FROM " + tableName + " WHERE " + joinCol + " = ?",
                            List.of(tableName),
                            joinCol + " = ?",
                            calledMethod
                    );
                    orderedStatements.add(implicitSelect);
                    break;
                }
            }
        }

        // IMPLICIT UPDATE: entity.setXxx() in a non-readOnly transaction
        if (methodName.startsWith("set") && methodName.length() > 3 && !rootTemplate.isReadOnly()) {
            String fieldName = Character.toLowerCase(methodName.charAt(3)) + methodName.substring(4);

            for (Map.Entry<String, String> entry : entityModel.getFieldToColumn().entrySet()) {
                String key = entry.getKey(); // "ClassName.fieldName"
                String columnName = entry.getValue();

                if (key.endsWith("." + fieldName)) {
                    String entityClassName = key.substring(0, key.indexOf('.'));
                    String tableName = entityModel.getEntityToTable().getOrDefault(entityClassName,
                            entityClassName.toLowerCase());

                    SqlOperation implicitUpdate = new SqlOperation(
                            "UPDATE",
                            "UPDATE " + tableName + " SET " + columnName + " = ? WHERE id = ?",
                            List.of(tableName),
                            "id = ?",
                            calledMethod
                    );
                    orderedStatements.add(implicitUpdate);
                    break;
                }
            }
        }
    }

    /**
     * Recursively parse all .java files under a directory.
     */
    private List<CompilationUnit> parseFromDir(String dirPath) {
        List<CompilationUnit> results = new ArrayList<>();
        parseFromDir(dirPath, results);
        return results;
    }

    private void parseFromDir(String dirPath, List<CompilationUnit> results) {
        File[] files = new File(dirPath).listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                parseFromDir(file.getAbsolutePath(), results);
            } else if (file.getName().toLowerCase().endsWith(".java")) {
                try {
                    CompilationUnit cu = StaticJavaParser.parse(file);
                    results.add(cu);
                } catch (ParseProblemException e) {
                    log.warn("Parse error in {}: {}", file.getPath(), e.getMessage());
                } catch (Exception e) {
                    log.warn("Error reading {}: {}", file.getPath(), e.getMessage());
                }
            }
        }
    }
}
