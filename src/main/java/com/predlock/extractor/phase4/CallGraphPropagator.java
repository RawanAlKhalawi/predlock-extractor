package com.predlock.extractor.phase4;

import com.github.javaparser.ParseProblemException;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
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
 */
public class CallGraphPropagator {

    private static final Logger log = LoggerFactory.getLogger(CallGraphPropagator.class);

    private static final Set<String> SERVICE_ANNOTS = Set.of(
        "Service", "Component", "Repository", "Controller", "RestController"
    );

    private static final Set<String> NON_REPO_SUFFIXES = Set.of(
        "Service", "ServiceImpl", "Manager", "ManagerImpl",
        "Handler", "HandlerImpl", "Processor", "ProcessorImpl",
        "Helper", "Util", "Utils", "Component"
    );

    private static final Set<String> OPAQUE_METHODS = Set.of(
        "createEntityInstance", "createEntityInstanceByConcreteClass",
        "newInstance", "getInstance", "forName"
    );

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

    public List<ResolvedTransaction> propagate(List<TransactionTemplate> templates,
                                               Map<String, List<SqlOperation>> sqlMap,
                                               EntityModel entityModel,
                                               String projectPath) {
        Map<String, String>  saveCallTypes     = new HashMap<>();
        Map<String, String>  saveFetchSources  = new HashMap<>();
        Map<String, String>  fieldToType       = new HashMap<>();
        Set<String>          newObjectVariables = new HashSet<>();
        Map<String, Integer> callSiteLines = new HashMap<>();

        // Static Java type of each method call's receiver expression, keyed by
        // "callerSig|calledMethod#occurrence" (with a "callerSig|calledMethod"
        // fallback). Used by inferImplicitSql to avoid setter-name collisions:
        // e.g. customer.setPassword must resolve to Customer (not AdminUser),
        // newAsset.setName to StaticAsset (not Store). A null entry here means
        // the receiver could not be resolved statically, in which case
        // inferImplicitSql skips emission rather than guessing by field name.
        Map<String, String>  callReceiverTypes = new HashMap<>();

        Map<String, List<String>> callGraph = buildCallGraph(
            projectPath, saveCallTypes, saveFetchSources,
            fieldToType, newObjectVariables, callSiteLines,
            callReceiverTypes);

        Map<String, TransactionTemplate> templateMap = new HashMap<>();
        for (TransactionTemplate t : templates) {
            templateMap.put(t.getMethodSignature(), t);
        }

        List<ResolvedTransaction> results = new ArrayList<>();
        for (TransactionTemplate template : templates) {
            ResolvedTransaction resolved = new ResolvedTransaction();
            resolved.setTransactionId(template.getMethodSignature());
            resolved.setPropagation(template.getPropagation());
            resolved.setReadOnly(template.isReadOnly());

            List<SqlOperation> orderedStatements = new ArrayList<>();
            List<String>       callChain         = new ArrayList<>();
            Set<String>        visited           = new HashSet<>();

            walkCallGraph(template.getMethodSignature(), callGraph, sqlMap, entityModel,
                templateMap, template, orderedStatements, callChain, visited,
                saveCallTypes, saveFetchSources, fieldToType, newObjectVariables,
                callSiteLines, callReceiverTypes);

            List<SqlOperation> merged = mergeImplicitSetterUpdates(
                orderedStatements, template.getMethodSignature());

            List<SqlOperation> deduped = new ArrayList<>();
            Set<String>        seen    = new HashSet<>();
            for (SqlOperation op : merged) {
                String key = op.getType() + "|" + op.getRawSql() + "|" + op.getTables();
                if (seen.add(key)) deduped.add(op);
            }

            List<String> dedupedChain = new ArrayList<>();
            Set<String>  seenMethods  = new HashSet<>();
            for (String method : callChain) {
                if (seenMethods.add(method)) dedupedChain.add(method);
            }

            resolved.setOrderedStatements(deduped);
            resolved.setCallChain(dedupedChain);
            results.add(resolved);
        }

        return results;
    }

    public List<ResolvedTransaction> propagate(List<TransactionTemplate> templates,
                                               Map<String, List<SqlOperation>> sqlMap,
                                               EntityModel entityModel) {
        return propagate(templates, sqlMap, entityModel, null);
    }

    private static final Set<String> SAVE_METHODS  = Set.of("save", "saveAll");
    private static final Set<String> FIND_PREFIXES = Set.of(
        "find", "get", "query", "search", "read", "stream", "count", "exists");

    private Map<String, List<String>> buildCallGraph(String projectPath,
                                                     Map<String, String> saveCallTypes,
                                                     Map<String, String> saveFetchSources,
                                                     Map<String, String> fieldToType,
                                                     Set<String> newObjectVariables,
                                                     Map<String, Integer> callSiteLines,
                                                     Map<String, String> callReceiverTypes) {
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

                    Map<String, String> fieldTypes = new HashMap<>();
                    for (FieldDeclaration field : cls.getFields()) {
                        String typeName = field.getElementType().asString();
                        for (VariableDeclarator vd : field.getVariables()) {
                            fieldTypes.put(vd.getNameAsString(), typeName);
                        }
                    }

                    String className = cls.getNameAsString();
                    fieldToType.putAll(fieldTypes);

                    for (MethodDeclaration method : cls.getMethods()) {
                        String sig = className + "." + method.getNameAsString();

                        if (method.getBody().isPresent()) {
                            for (VariableDeclarator vd : method.findAll(VariableDeclarator.class)) {
                                if (vd.getInitializer().isPresent()
                                    && vd.getInitializer().get() instanceof ObjectCreationExpr) {
                                    newObjectVariables.add(sig + ":" + vd.getNameAsString());
                                }
                            }
                        }

                        List<MethodCallExpr> sortedCalls = method.findAll(MethodCallExpr.class)
                            .stream()
                            .sorted(Comparator.comparingInt(
                                mce -> mce.getBegin().map(p -> p.line).orElse(0)))
                            .toList();

                        List<String>       calls              = new ArrayList<>();
                        Map<String, Integer> callOccurrence   = new HashMap<>();

                        for (MethodCallExpr mce : sortedCalls) {
                            int callLine = mce.getBegin().map(p -> p.line).orElse(0);

                            // Resolve the receiver's static Java type once per call expression.
                            // Result may be null (chained expressions, static calls, etc.).
                            String receiverType = resolveReceiverStaticType(mce, method, fieldTypes);

                            String callStr;
                            if (mce.getScope().isPresent()) {
                                String scopeName = mce.getScope().get().toString();
                                callStr = scopeName + "." + mce.getNameAsString();

                                String resolvedType = fieldTypes.get(scopeName);
                                if (resolvedType != null && !resolvedType.equals(scopeName)) {
                                    String resolvedCallStr = resolvedType + "." + mce.getNameAsString();
                                    int rOcc = callOccurrence.merge(resolvedCallStr, 1, Integer::sum);
                                    callSiteLines.put(sig + "|" + resolvedCallStr + "#" + rOcc, callLine);
                                    callSiteLines.put(sig + "|" + resolvedCallStr, callLine); // last-write fallback
                                    if (receiverType != null) {
                                        callReceiverTypes.put(sig + "|" + resolvedCallStr + "#" + rOcc, receiverType);
                                        callReceiverTypes.put(sig + "|" + resolvedCallStr, receiverType);
                                    }
                                    calls.add(resolvedCallStr);
                                }
                            } else {
                                callStr = mce.getNameAsString();
                            }

                            int occ = callOccurrence.merge(callStr, 1, Integer::sum);
                            callSiteLines.put(sig + "|" + callStr + "#" + occ, callLine);
                            callSiteLines.put(sig + "|" + callStr, callLine); // last-write fallback
                            if (receiverType != null) {
                                callReceiverTypes.put(sig + "|" + callStr + "#" + occ, receiverType);
                                callReceiverTypes.put(sig + "|" + callStr, receiverType);
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

    private void analyzeSaveCalls(MethodDeclaration method, String methodSig,
                                  Map<String, String> saveCallTypes,
                                  Map<String, String> saveFetchSources,
                                  Map<String, String> fieldTypes) {
        List<Statement> statements     = method.getBody().get().getStatements();
        Set<String>     alreadySavedVars = new HashSet<>();

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
                String fetchSource = traceFetchSource(
                    nameExpr.getNameAsString(), statements, mce, fieldTypes);
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
            Statement stmt   = statements.get(i);
            int stmtLine     = stmt.getBegin().map(p -> p.line).orElse(0);
            if (stmtLine >= saveLineNo) continue;
            if (!(stmt instanceof ExpressionStmt exprStmt)) continue;

            Expression expr        = exprStmt.getExpression();
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
        if (arg instanceof ObjectCreationExpr) return "INSERT";
        if (arg instanceof NameExpr nameExpr)  return traceVariableOrigin(nameExpr.getNameAsString(), statements, saveCall);
        if (arg instanceof MethodCallExpr)     return "UPDATE";
        return "UPDATE";
    }

    private String traceVariableOrigin(String varName, List<Statement> statements,
                                       MethodCallExpr saveCall) {
        int saveLineNo = saveCall.getBegin().map(p -> p.line).orElse(Integer.MAX_VALUE);

        // Walk ALL VariableDeclarators in the enclosing method (not just top-level
        // statements), since `T history = new T(...)` may be nested inside an
        // if/for/try block. Pick the declarator with the largest line number that
        // is still < saveLineNo (handles shadowing and reassignment patterns).
        MethodDeclaration enclosing = saveCall.findAncestor(MethodDeclaration.class).orElse(null);
        if (enclosing != null) {
            VariableDeclarator best = null;
            int bestLine = -1;
            for (VariableDeclarator vd : enclosing.findAll(VariableDeclarator.class)) {
                if (!vd.getNameAsString().equals(varName)) continue;
                if (!vd.getInitializer().isPresent()) continue;
                int line = vd.getBegin().map(p -> p.line).orElse(-1);
                if (line >= 0 && line < saveLineNo && line > bestLine) {
                    bestLine = line;
                    best = vd;
                }
            }
            if (best != null) {
                return classifyInitializer(best.getInitializer().get());
            }

            // Also walk all assignments in the method body. Pick the latest one
            // before the save call.
            AssignExpr bestAssign = null;
            int bestAssignLine = -1;
            for (AssignExpr ae : enclosing.findAll(AssignExpr.class)) {
                if (!ae.getTarget().toString().equals(varName)) continue;
                int line = ae.getBegin().map(p -> p.line).orElse(-1);
                if (line >= 0 && line < saveLineNo && line > bestAssignLine) {
                    bestAssignLine = line;
                    bestAssign = ae;
                }
            }
            if (bestAssign != null && bestAssignLine > bestLine) {
                return classifyInitializer(bestAssign.getValue());
            }

            // Method parameters fall through to UPDATE (entity passed in).
            for (Parameter p : enclosing.getParameters()) {
                if (p.getNameAsString().equals(varName)) return "UPDATE";
            }
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
                               Set<String> newObjectVariables,
                               Map<String, Integer> callSiteLines,
                               Map<String, String> callReceiverTypes) {
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

        Set<String>          typeNames          = new HashSet<>(fieldToType.values());
        Map<String, Integer> walkSaveOccurrence = new HashMap<>();
        Map<String, Integer> walkCallOccurrence = new HashMap<>();

        for (String calledMethod : calledMethods) {
            int callOcc = walkCallOccurrence.merge(calledMethod, 1, Integer::sum);

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

            int callSiteLine = callSiteLines.getOrDefault(
                methodSig + "|" + calledMethod + "#" + callOcc,
                callSiteLines.getOrDefault(methodSig + "|" + calledMethod, 0));

            if (!isSetterOnNewObject(calledMethod, methodSig, newObjectVariables)) {
                // Look up the static type of the receiver expression. null means
                // the call was chained / unresolvable (e.g. a.getB().setC(...)),
                // in which case inferImplicitSql will skip emission.
                String receiverType = callReceiverTypes.getOrDefault(
                    methodSig + "|" + calledMethod + "#" + callOcc,
                    callReceiverTypes.get(methodSig + "|" + calledMethod));

                int sizeBefore = orderedStatements.size();
                inferImplicitSql(calledMethod, entityModel, rootTemplate,
                    orderedStatements, receiverType);

                if (callSiteLine > 0) {
                    for (int i = sizeBefore; i < orderedStatements.size(); i++) {
                        SqlOperation newOp = orderedStatements.get(i);
                        if (isImplicitSetterUpdate(newOp) && newOp.getLineNumber() == 0) {
                            newOp.setLineNumber(callSiteLine);
                        }
                    }
                }
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
                        if (callSiteLine == 0) {
                            callSiteLine = callSiteLines.getOrDefault(
                                methodSig + "|" + resolvedKey + "#" + callOcc,
                                callSiteLines.getOrDefault(methodSig + "|" + resolvedKey, 0));
                        }
                        // Fallback: Phase 3 emits SQL ops for em.merge / @Query / etc. keyed
                        // under the IMPLEMENTING class (e.g. CustomerDaoImpl.save), but the
                        // calling field is typically declared as the INTERFACE
                        // (e.g. customerDao : CustomerDao). Try the conventional "Impl"
                        // suffix so Phase 3's emissions reach interface-typed callers.
                        if (calledOps == null && !typeName.endsWith("Impl")) {
                            String implKey = typeName + "Impl" + calledMethod.substring(dot);
                            calledOps = sqlMap.get(implKey);
                        }
                    }
                }
            }

            if (calledOps != null) {
                for (SqlOperation op : calledOps) {
                    int effectiveLine = callSiteLine > 0 ? callSiteLine : op.getLineNumber();
                    orderedStatements.add(new SqlOperation(
                        op.getType(), op.getRawSql(), op.getTables(),
                        op.getWhereCondition(), op.getSourceMethod(), effectiveLine));
                }
                if (!callChain.contains(calledMethod)) callChain.add(calledMethod);

            } else if (callSiteLine > 0
                && isFindMethodCall(calledMethod)
                && isRepositoryCall(calledMethod, fieldToType)) {
                String tableName = inferTableFromCall(calledMethod, entityModel, fieldToType);
                if (tableName != null) {
                    orderedStatements.add(new SqlOperation(
                        "SELECT",
                        "SELECT * FROM " + tableName + " WHERE id = ?",
                        List.of(tableName), "id = ?", calledMethod, callSiteLine));
                    if (!callChain.contains(calledMethod)) callChain.add(calledMethod);
                }
            }

            {
                int    sDot    = calledMethod.lastIndexOf('.');
                String sScope  = sDot > 0 ? calledMethod.substring(0, sDot) : "";
                String sMethod = sDot > 0 ? calledMethod.substring(sDot + 1) : calledMethod;
                if (!(SAVE_METHODS.contains(sMethod) && typeNames.contains(sScope))) {
                    int saveOcc = walkSaveOccurrence.getOrDefault(calledMethod, 0);
                    inferSaveType(calledMethod, methodSig, saveCallTypes, saveFetchSources,
                        entityModel, orderedStatements, sqlMap, fieldToType, saveOcc,
                        callSiteLine, callSiteLines);
                }
            }

            if (callGraph.containsKey(calledMethod) && !visited.contains(calledMethod)) {
                int    oDot    = calledMethod.lastIndexOf('.');
                String oMethod = oDot > 0 ? calledMethod.substring(oDot + 1) : calledMethod;
                if (OPAQUE_METHODS.contains(oMethod)) continue;
                walkCallGraph(calledMethod, callGraph, sqlMap, entityModel,
                    templateMap, rootTemplate, orderedStatements, callChain, visited,
                    saveCallTypes, saveFetchSources, fieldToType, newObjectVariables,
                    callSiteLines, callReceiverTypes);
            }
        }
    }


    /**
     * Models Hibernate's {@code em.merge(detachedEntity)} (and Spring Data
     * {@code repository.save(existingEntity)} on top of it) as the
     * SELECT-then-UPDATE pair it actually is at runtime.
     *
     * <p>The runtime sequence inside merge is:
     * <ol>
     *   <li><b>SELECT</b> the row to load its current state into the
     *       persistence context — acquires a shared row lock.</li>
     *   <li><b>UPDATE</b> the row at flush — wants an exclusive row lock,
     *       upgrading from the shared lock just held.</li>
     * </ol>
     * Two concurrent calls each take the shared lock, then both want to
     * upgrade — a textbook hold-and-wait cycle that the WeSEER analyzer
     * recognises as a real deadlock. Modeling save as a single UPDATE
     * statement collapses this pair and makes the analyzer reject the
     * cycle for failing the hold-and-wait structural check.
     *
     * <p>The helper is a no-op when {@code orderedStatements} already
     * contains a SELECT on {@code tableName}: the entity is already loaded
     * in the persistence context (via an explicit find, an earlier merge,
     * a Phase 3 emission propagated through the call graph, or a
     * fetchSource trace) and merge would not re-fetch.
     *
     * <p>The SELECT is inserted at position 0 so it precedes any setter
     * UPDATEs that were emitted earlier during the walk. After
     * {@link #mergeImplicitSetterUpdates}, the result for a typical
     * saveCustomer-style method is the clean two-statement transaction
     * {@code [SELECT, UPDATE_combined]}.
     */
    private void emitImplicitMergeSelect(List<SqlOperation> orderedStatements,
                                         String tableName,
                                         String sourceMethod,
                                         int callSiteLine) {
        if (tableName == null) return;

        for (SqlOperation op : orderedStatements) {
            if ("SELECT".equals(op.getType())
                && op.getTables() != null
                && op.getTables().contains(tableName)) {
                return; // already loaded in the persistence context
            }
        }

        orderedStatements.add(0, new SqlOperation(
            "SELECT",
            "SELECT * FROM " + tableName + " WHERE id = ?",
            List.of(tableName), "id = ?", sourceMethod, callSiteLine));
    }

    private void inferSaveType(String calledMethod, String callerMethodSig,
                               Map<String, String> saveCallTypes,
                               Map<String, String> saveFetchSources,
                               EntityModel entityModel,
                               List<SqlOperation> orderedStatements,
                               Map<String, List<SqlOperation>> sqlMap,
                               Map<String, String> fieldToType,
                               int occurrence,
                               int callSiteLine,
                               Map<String, Integer> callSiteLines) {
        int dotIdx = calledMethod.lastIndexOf('.');
        if (dotIdx < 0) return;
        String methodName = calledMethod.substring(dotIdx + 1);
        if (!SAVE_METHODS.contains(methodName)) return;

        String occKey = calledMethod + "#" + occurrence;
        String type = saveCallTypes.get(callerMethodSig + ":" + occKey);
        if (type == null) type = saveCallTypes.get(callerMethodSig + ":" + calledMethod);
        if (type == null) type = saveCallTypes.get(calledMethod);
        if (type == null) return;

        if ("UPDATE".equals(type) && callSiteLine > 0) {
            for (SqlOperation op : orderedStatements) {
                if ("UPDATE".equals(op.getType())
                    && op.getSourceMethod() != null
                    && op.getSourceMethod().contains(".set")
                    && op.getLineNumber() == 0) {
                    op.setLineNumber(callSiteLine);
                }
            }
        }

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
                        int fetchLine = callSiteLines.getOrDefault(
                            callerMethodSig + "|" + fetchSource,
                            callSiteLine);
                        orderedStatements.add(0, new SqlOperation(
                            "SELECT",
                            "SELECT * FROM " + tableName + " WHERE id = ?",
                            List.of(tableName), "id = ?", fetchSource, fetchLine));
                    }
                }
            }

            // Even when no explicit fetchSource was traced (typical for service
            // methods that take the entity as a parameter, e.g. saveCustomer(c)),
            // Hibernate's em.merge(detachedEntity) issues an implicit SELECT
            // before the flush UPDATE. Inject it now so the lock-upgrade pattern
            // becomes visible to the WeSEER analyzer's hold-and-wait check.
            // The helper is a no-op when a SELECT on the target table already
            // precedes the save (covers explicit-find, fetchSource, and Phase 3
            // propagation cases).
            String mergeRepoScope = calledMethod.substring(0, dotIdx);
            String mergeTable = inferTableFromRepo(mergeRepoScope, entityModel, fieldToType);
            if ("UPDATE".equals(type)) {
                emitImplicitMergeSelect(orderedStatements, mergeTable, calledMethod, callSiteLine);
            }        }

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
                        List.of(tableName), null, calledMethod, callSiteLine));
                } else if ("UPDATE".equals(type)) {
                    String setClause = buildSetClauseFromSetters(orderedStatements);
                    orderedStatements.add(new SqlOperation(
                        "UPDATE", "UPDATE " + tableName + " SET " + setClause + " WHERE id = ?",
                        List.of(tableName), "id = ?", calledMethod, callSiteLine));
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

    private String inferTableFromRepo(String repoName, EntityModel entityModel,
                                      Map<String, String> fieldToType) {
        String typeName = fieldToType.getOrDefault(repoName, repoName);

        for (String suffix : NON_REPO_SUFFIXES) {
            if (typeName.endsWith(suffix)) {
                log.debug("Skipping non-repo type '{}' in inferTableFromRepo", typeName);
                return null;
            }
        }

        String base = typeName;
        for (String suffix : DAO_TYPE_SUFFIXES) {
            if (typeName.endsWith(suffix) && typeName.length() > suffix.length()) {
                base = typeName.substring(0, typeName.length() - suffix.length());
                break;
            }
        }

        String table = entityModel.getEntityToTable().get(base);
        if (table != null) return table;

        table = entityModel.getEntityToTable().get(base + "Impl");
        if (table != null) return table;

        if (base.equals(typeName)) return camelToSnake(base);
        return camelToSnake(base);
    }

    /**
     * Emits implicit SQL for getter / setter calls against a JPA entity.
     *
     * <p><b>Scoped lookup (fix for setter-name collisions).</b>
     * The previous implementation iterated all of {@code entityModel.getFieldToColumn()}
     * and stopped at the first key ending with {@code ".<fieldName>"}. That strategy
     * produced phantom UPDATE edges whenever multiple entities declared a setter of
     * the same name (e.g. {@code Customer.setPassword} vs {@code AdminUser.setPassword},
     * {@code StaticAsset.setName} vs {@code Store.setName},
     * {@code IdGeneration.setType} vs {@code ProductOption.setType}).
     *
     * <p>We now require a resolved static type for the receiver and look up the field
     * only under that type's key. If {@code receiverStaticType} is null (chained call,
     * static method, unresolvable scope) or does not correspond to a known
     * {@code @Entity}, we skip emission rather than guess. This intentionally costs a
     * small number of true positives (e.g. setters reached only through casted scopes
     * the resolver could not infer) in exchange for eliminating an entire class of
     * phantom deadlocks.
     *
     * @param receiverStaticType simple or fully-qualified type name of the receiver
     *                           expression as seen at the call site; may be null
     */
    private void inferImplicitSql(String calledMethod, EntityModel entityModel,
                                  TransactionTemplate rootTemplate,
                                  List<SqlOperation> orderedStatements,
                                  String receiverStaticType) {
        int dotIdx = calledMethod.lastIndexOf('.');
        if (dotIdx < 0) return;

        String methodName = calledMethod.substring(dotIdx + 1);

        // Accessors (get / set) make sense only against an @Entity receiver.
        // If we cannot prove that, don't emit anything.
        boolean isGetter = methodName.startsWith("get") && methodName.length() > 3;
        boolean isSetter = methodName.startsWith("set") && methodName.length() > 3
            && !rootTemplate.isReadOnly();
        if (!isGetter && !isSetter) return;

        String entityKey = findEntityKey(receiverStaticType, entityModel);
        if (entityKey == null) {
            // Receiver is a DTO, primitive, @Transient field holder, or an
            // unresolvable expression. Do not emit implicit SQL.
            return;
        }

        if (IMPLICIT_UPDATE_ENTITY_BLACKLIST.contains(entityKey)) {
            log.debug("Skipping implicit SQL for blacklisted entity '{}'", entityKey);
            return;
        }

        String fieldName =
            Character.toLowerCase(methodName.charAt(3)) + methodName.substring(4);

        // Try the resolved key first, then its Impl/base counterpart, since the
        // entity model may index fields under either form.
        String altKey = entityKey.endsWith("Impl")
            ? entityKey.substring(0, entityKey.length() - 4)
            : entityKey + "Impl";

        // IMPLICIT SELECT: entity.getXxx() where xxx is a LAZY relationship
        if (isGetter) {
            RelationshipInfo rel = entityModel.getRelationships().get(entityKey + "." + fieldName);
            if (rel == null) {
                rel = entityModel.getRelationships().get(altKey + "." + fieldName);
            }
            if (rel == null || !"LAZY".equals(rel.getFetchType())) return;

            String targetEntity = rel.getTargetEntity();
            String tableName = entityModel.getEntityToTable().getOrDefault(
                targetEntity,
                targetEntity != null ? targetEntity.toLowerCase() : "unknown");
            String joinCol = rel.getJoinColumn() != null ? rel.getJoinColumn() : "id";

            orderedStatements.add(new SqlOperation(
                "SELECT",
                "SELECT * FROM " + tableName + " WHERE " + joinCol + " = ?",
                List.of(tableName), joinCol + " = ?", calledMethod));
            return;
        }

        // IMPLICIT UPDATE: entity.setXxx() on a persistent (non-@Transient) field.
        // Only emit ONE UPDATE per table; Hibernate dirty-checking flushes all
        // changed fields of a given entity in a single SQL statement (the
        // mergeImplicitSetterUpdates pass collapses multiple setter emissions
        // for the same table into a single combined UPDATE).
        String columnName = entityModel.getFieldToColumn().get(entityKey + "." + fieldName);
        if (columnName == null) {
            columnName = entityModel.getFieldToColumn().get(altKey + "." + fieldName);
        }
        if (columnName == null) {
            // Field is not persistent on this entity (e.g. it is @Transient, or the
            // method name does not correspond to a mapped column at all). Skip.
            return;
        }

        String tableName = entityModel.getEntityToTable().get(entityKey);
        if (tableName == null) tableName = entityModel.getEntityToTable().get(altKey);
        if (tableName == null) return;

        orderedStatements.add(new SqlOperation(
            "UPDATE",
            "UPDATE " + tableName + " SET " + columnName + " = ? WHERE id = ?",
            List.of(tableName), "id = ?", calledMethod));
    }

    // -------------------------------------------------------------------------
    // Static-type resolution helpers (introduced for the setter-collision fix)
    // -------------------------------------------------------------------------

    /**
     * Resolves the static Java type of {@code mce}'s receiver expression.
     *
     * <p>Supported shapes:
     * <ul>
     *   <li>{@code ((Foo) x).bar()} and {@code (Foo) x} — cast expression</li>
     *   <li>{@code x.bar()} where {@code x} is a parameter, a local variable, or a field</li>
     *   <li>{@code this.bar()} / {@code bar()} (implicit this) — the enclosing class</li>
     *   <li>{@code new Foo().bar()} — the constructed type</li>
     * </ul>
     * Everything else (chained calls, static method access, array access, etc.)
     * returns null, and the caller must treat that as "cannot resolve" and skip
     * anything that depends on the receiver's entity identity.
     */
    private String resolveReceiverStaticType(MethodCallExpr mce, MethodDeclaration method,
                                             Map<String, String> fieldTypes) {
        if (mce == null || method == null) return null;

        // Implicit this: foo() called from inside the enclosing class.
        if (!mce.getScope().isPresent()) {
            return method.findAncestor(ClassOrInterfaceDeclaration.class)
                .map(ClassOrInterfaceDeclaration::getNameAsString).orElse(null);
        }

        Expression scope = mce.getScope().get();
        // Unwrap parentheses like ((Foo) x).bar().
        while (scope instanceof EnclosedExpr ee) {
            scope = ee.getInner();
        }

        if (scope instanceof CastExpr ce) {
            return ce.getType().asString();
        }
        if (scope instanceof ObjectCreationExpr oce) {
            return oce.getType().asString();
        }
        if (scope instanceof ThisExpr) {
            return method.findAncestor(ClassOrInterfaceDeclaration.class)
                .map(ClassOrInterfaceDeclaration::getNameAsString).orElse(null);
        }
        if (scope instanceof NameExpr ne) {
            String name = ne.getNameAsString();

            // Method parameters.
            for (Parameter p : method.getParameters()) {
                if (p.getNameAsString().equals(name)) {
                    return p.getType().asString();
                }
            }

            // Local variables: pick the declaration with the greatest begin-line
            // that is still before the call, which correctly handles the common
            // "reassign then call" pattern and the dominant case of no shadowing.
            int callLine = mce.getBegin().map(p -> p.line).orElse(Integer.MAX_VALUE);
            VariableDeclarator best = null;
            int bestLine = -1;
            for (VariableDeclarator vd : method.findAll(VariableDeclarator.class)) {
                if (!vd.getNameAsString().equals(name)) continue;
                int line = vd.getBegin().map(p -> p.line).orElse(-1);
                if (line <= callLine && line > bestLine) {
                    bestLine = line;
                    best = vd;
                }
            }
            if (best != null) return best.getType().asString();

            // Fields of the enclosing service class.
            return fieldTypes.get(name);
        }

        // Chained calls (a.b().c()), field accesses (obj.field.x()), array
        // access, etc. — not resolved statically.
        return null;
    }

    /**
     * Normalises {@code typeName} and returns a key under which
     * {@code entityModel.getEntityToTable()} has an entry, or null if no such
     * entry exists. Tries the plain name first, then the conventional
     * {@code Impl} suffix used by Broadleaf-style domains.
     */
    private String findEntityKey(String typeName, EntityModel entityModel) {
        if (typeName == null) return null;

        // Strip qualified prefix (e.g. org.foo.Bar -> Bar).
        int lastDot = typeName.lastIndexOf('.');
        if (lastDot >= 0) typeName = typeName.substring(lastDot + 1);
        // Strip generic parameters (e.g. List<Foo> -> List).
        int lt = typeName.indexOf('<');
        if (lt >= 0) typeName = typeName.substring(0, lt);
        typeName = typeName.trim();
        if (typeName.isEmpty()) return null;

        if (entityModel.getEntityToTable().containsKey(typeName)) return typeName;
        if (entityModel.getEntityToTable().containsKey(typeName + "Impl")) {
            return typeName + "Impl";
        }
        if (typeName.endsWith("Impl")) {
            String base = typeName.substring(0, typeName.length() - 4);
            if (entityModel.getEntityToTable().containsKey(base)) return base;
        }
        return null;
    }

    private List<SqlOperation> mergeImplicitSetterUpdates(List<SqlOperation> ops,
                                                          String transactionMethodSig) {
        if (ops == null || ops.size() < 2) return ops;

        Map<String, List<SqlOperation>> setterGroupsByTable = new LinkedHashMap<>();
        for (SqlOperation op : ops) {
            if (isImplicitSetterUpdate(op)) {
                String table = op.getTables().get(0);
                setterGroupsByTable.computeIfAbsent(table, k -> new ArrayList<>()).add(op);
            }
        }

        boolean anyMergeNeeded = setterGroupsByTable.values().stream()
            .anyMatch(g -> g.size() > 1);
        if (!anyMergeNeeded) return ops;

        Set<SqlOperation> consumed = Collections.newSetFromMap(new IdentityHashMap<>());
        List<SqlOperation> result  = new ArrayList<>(ops.size());

        for (SqlOperation op : ops) {
            if (consumed.contains(op)) continue;

            if (!isImplicitSetterUpdate(op)) {
                result.add(op);
                continue;
            }

            String             table = op.getTables().get(0);
            List<SqlOperation> group = setterGroupsByTable.get(table);

            if (group == null || group.size() <= 1) {
                result.add(op);
                continue;
            }

            if (!consumed.containsAll(group)) {
                List<String> setCols = new ArrayList<>();
                for (SqlOperation setter : group) {
                    extractSetColumn(setter.getRawSql()).ifPresent(col -> {
                        if (!setCols.contains(col)) setCols.add(col);
                    });
                }

                String mergedSql = "UPDATE " + table + " SET "
                    + String.join(", ", setCols) + " WHERE id = ?";

                int mergedLine = group.stream()
                    .mapToInt(SqlOperation::getLineNumber)
                    .filter(l -> l > 0)
                    .findFirst()
                    .orElse(0);

                result.add(new SqlOperation(
                    "UPDATE", mergedSql, List.of(table), "id = ?",
                    transactionMethodSig, mergedLine));

                consumed.addAll(group);
            }
        }

        return result;
    }

    private boolean isImplicitSetterUpdate(SqlOperation op) {
        return "UPDATE".equals(op.getType())
            && op.getSourceMethod() != null
            && op.getSourceMethod().contains(".set")
            && op.getTables() != null
            && !op.getTables().isEmpty();
    }

    private java.util.Optional<String> extractSetColumn(String rawSql) {
        if (rawSql == null) return java.util.Optional.empty();
        int setIdx   = rawSql.toUpperCase().indexOf(" SET ");
        int whereIdx = rawSql.toUpperCase().indexOf(" WHERE ");
        if (setIdx < 0) return java.util.Optional.empty();
        int    end = whereIdx > setIdx ? whereIdx : rawSql.length();
        String col = rawSql.substring(setIdx + 5, end).trim();
        return col.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(col);
    }

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

    private boolean isFindMethodCall(String calledMethod) {
        int dot = calledMethod.lastIndexOf('.');
        if (dot < 0) return false;
        String methodName = calledMethod.substring(dot + 1);
        return isFindMethod(methodName);
    }

    private String inferTableFromCall(String calledMethod, EntityModel entityModel,
                                      Map<String, String> fieldToType) {
        int dot = calledMethod.lastIndexOf('.');
        if (dot < 0) return null;
        String scope = calledMethod.substring(0, dot);
        return inferTableFromRepo(scope, entityModel, fieldToType);
    }

    private boolean isRepositoryCall(String calledMethod, Map<String, String> fieldToType) {
        int dot = calledMethod.lastIndexOf('.');
        if (dot < 0) return false;
        String scope    = calledMethod.substring(0, dot);
        String typeName = fieldToType.getOrDefault(scope, scope);
        for (String suffix : NON_REPO_SUFFIXES) {
            if (typeName.endsWith(suffix)) return false;
        }
        for (String suffix : DAO_TYPE_SUFFIXES) {
            if (typeName.endsWith(suffix)) return true;
        }
        return false;
    }
}