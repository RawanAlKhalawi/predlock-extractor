package com.predlock.extractor.phase3;

import com.github.javaparser.ParseProblemException;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.predlock.extractor.model.EntityModel;
import com.predlock.extractor.model.RelationshipInfo;
import com.predlock.extractor.model.SqlOperation;
import com.predlock.extractor.util.ParseUtils;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.asm.ClassWriter;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.data.mapping.PropertyPath;
import org.springframework.data.repository.query.parser.Part;
import org.springframework.data.repository.query.parser.PartTree;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 3: Collects SQL queries from Spring Data repositories, {@code @Query} annotations,
 * JdbcTemplate calls, EntityManager calls, named queries, and EM lifecycle methods.
 * <p>
 * Handles six forms:
 * <ul>
 *   <li>Form A — {@code @Query} with {@code nativeQuery=true}: parsed with JSQLParser</li>
 *   <li>Form B — {@code @Query} with JPQL: entity names mapped to tables via EntityModel</li>
 *   <li>Form C — Spring Data derived query method names: parsed via naming convention</li>
 *   <li>Form D — JdbcTemplate / EntityManager string arguments: parsed with JSQLParser</li>
 *   <li>Form E — {@code @NamedQuery} / {@code @NamedNativeQuery} resolved via {@code createNamedQuery}</li>
 *   <li>Form F — EM lifecycle methods: {@code persist}, {@code merge}, {@code remove}, {@code find}</li>
 * </ul>
 */
public class SqlQueryCollector {

    private static final Logger log = LoggerFactory.getLogger(SqlQueryCollector.class);

    private static final Set<String> REPO_ANNOTATIONS = Set.of(
        "Repository", "RepositoryRestResource"
    );

    private static final Set<String> REPO_SUPERINTERFACES = Set.of(
        "JpaRepository", "CrudRepository", "PagingAndSortingRepository",
        "Repository", "JpaSpecificationExecutor"
    );

    private static final Set<String> JDBC_METHODS = Set.of(
        "query", "queryForObject", "queryForList", "queryForMap",
        "update", "execute", "batchUpdate"
    );

    private static final Set<String> EM_METHODS = Set.of(
        "createQuery", "createNativeQuery"
    );

    /**
     * Form F — EM lifecycle methods mapped to their effective SQL operation type.
     * {@code merge} is conservatively treated as UPDATE (JPA upsert semantics).
     */
    private static final Map<String, String> EM_LIFECYCLE_METHODS = Map.of(
        "persist", "INSERT",
        "merge",   "UPDATE",
        "remove",  "DELETE",
        "find",    "SELECT"
    );

    private static final Set<String> JAVA_TYPES = Set.of(
        "String", "Integer", "Long", "Double", "Float", "Boolean",
        "Object", "List", "Set", "Map", "Collection", "Optional",
        "Void", "Class", "Enum", "Number"
    );

    /**
     * Suffixes stripped from type/variable names when resolving to entity names.
     * Tried in order — first match wins.
     */
    private static final List<String> ENTITY_TYPE_SUFFIXES = List.of(
        "Impl", "Dao", "Entity", "Repository"
    );

    private static final Pattern CAMEL_SPLIT = Pattern.compile("(?<=[a-z])(?=[A-Z])");

    // -------------------------------------------------------------------------
    // Named query registry
    // -------------------------------------------------------------------------

    /**
     * Holds the SQL (or JPQL) string and whether it is a native query,
     * keyed by the named query's logical name.
     */
    private record NamedQueryEntry(String sql, boolean isNative) {}

    // -------------------------------------------------------------------------
    // Dynamic ClassLoader for PartTree reflection
    // -------------------------------------------------------------------------

    private static class DynClassLoader extends ClassLoader {
        DynClassLoader(ClassLoader parent) { super(parent); }
        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    /** Single shared loader — all dynamic classes must be defined through this instance. */
    private static final DynClassLoader SHARED_LOADER =
        new DynClassLoader(SqlQueryCollector.class.getClassLoader());

    private static int dynClassCounter = 0;

    // -------------------------------------------------------------------------
    // Dynamic entity class builder
    // -------------------------------------------------------------------------

    private static Class<?> buildDynamicEntityClass(String entityName,
                                                    List<String> simpleFields,
                                                    Map<String, Class<?>> typedFields) {
        String className    = "com.predlock.dyn." + entityName + "_" + (dynClassCounter++);
        String internalName = className.replace('.', '/');
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);

        // Default constructor
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        for (String field : simpleFields) {
            emitFieldWithAccessors(cw, internalName, field, "Ljava/lang/Object;", "()Ljava/lang/Object;");
        }

        for (Map.Entry<String, Class<?>> entry : typedFields.entrySet()) {
            String field      = entry.getKey();
            Class<?> type     = entry.getValue();
            String descriptor = "L" + type.getName().replace('.', '/') + ";";
            emitFieldWithAccessors(cw, internalName, field, descriptor, "()" + descriptor);
        }

        cw.visitEnd();
        return SHARED_LOADER.define(className, cw.toByteArray());
    }

    private static void emitFieldWithAccessors(ClassWriter cw, String ownerInternal,
                                               String fieldName,
                                               String fieldDescriptor,
                                               String getterDescriptor) {
        cw.visitField(Opcodes.ACC_PRIVATE, fieldName, fieldDescriptor, null, null).visitEnd();

        String cap = Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);

        MethodVisitor gmv = cw.visitMethod(Opcodes.ACC_PUBLIC, "get" + cap, getterDescriptor, null, null);
        gmv.visitCode();
        gmv.visitVarInsn(Opcodes.ALOAD, 0);
        gmv.visitFieldInsn(Opcodes.GETFIELD, ownerInternal, fieldName, fieldDescriptor);
        gmv.visitInsn(Opcodes.ARETURN);
        gmv.visitMaxs(0, 0);
        gmv.visitEnd();

        MethodVisitor smv = cw.visitMethod(Opcodes.ACC_PUBLIC, "set" + cap, "(Ljava/lang/Object;)V", null, null);
        smv.visitCode();
        smv.visitVarInsn(Opcodes.ALOAD, 0);
        smv.visitVarInsn(Opcodes.ALOAD, 1);
        if (!fieldDescriptor.equals("Ljava/lang/Object;")) {
            String castTarget = fieldDescriptor.substring(1, fieldDescriptor.length() - 1);
            smv.visitTypeInsn(Opcodes.CHECKCAST, castTarget);
        }
        smv.visitFieldInsn(Opcodes.PUTFIELD, ownerInternal, fieldName, fieldDescriptor);
        smv.visitInsn(Opcodes.RETURN);
        smv.visitMaxs(0, 0);
        smv.visitEnd();
    }

    // -------------------------------------------------------------------------
    // Named query pre-scan (static helpers — no SqlVisitor instance needed)
    // -------------------------------------------------------------------------

    /**
     * Scans a class for {@code @NamedQuery}, {@code @NamedNativeQuery},
     * {@code @NamedQueries}, and {@code @NamedNativeQueries} and registers
     * every entry into {@code map}.
     */
    private static void extractNamedQueries(ClassOrInterfaceDeclaration cls,
                                            Map<String, NamedQueryEntry> map) {
        for (AnnotationExpr ann : cls.getAnnotations()) {
            String annName = ann.getNameAsString();
            switch (annName) {
                case "NamedQuery"         -> parseAndRegisterNamedQuery(ann, false, map);
                case "NamedNativeQuery"   -> parseAndRegisterNamedQuery(ann, true,  map);
                case "NamedQueries",
                     "NamedNativeQueries" -> {
                    if (ann instanceof SingleMemberAnnotationExpr single) {
                        collectNestedAnnotations(single.getMemberValue(), map);
                    } else if (ann instanceof NormalAnnotationExpr normal) {
                        for (MemberValuePair mvp : normal.getPairs()) {
                            if (mvp.getNameAsString().equals("value")) {
                                collectNestedAnnotations(mvp.getValue(), map);
                            }
                        }
                    }
                }
            }
        }
    }

    private static void collectNestedAnnotations(Expression expr,
                                                 Map<String, NamedQueryEntry> map) {
        if (expr instanceof ArrayInitializerExpr arr) {
            for (Expression e : arr.getValues()) {
                if (e instanceof AnnotationExpr ann) {
                    boolean isNative = ann.getNameAsString().equals("NamedNativeQuery");
                    parseAndRegisterNamedQuery(ann, isNative, map);
                }
            }
        } else if (expr instanceof AnnotationExpr ann) {
            boolean isNative = ann.getNameAsString().equals("NamedNativeQuery");
            parseAndRegisterNamedQuery(ann, isNative, map);
        }
    }

    private static void parseAndRegisterNamedQuery(AnnotationExpr ann, boolean isNative,
                                                   Map<String, NamedQueryEntry> map) {
        String name = null, query = null;
        if (ann instanceof NormalAnnotationExpr normal) {
            for (MemberValuePair mvp : normal.getPairs()) {
                if (mvp.getNameAsString().equals("name"))
                    name  = extractStringLiteralStatic(mvp.getValue());
                else if (mvp.getNameAsString().equals("query"))
                    query = extractStringLiteralStatic(mvp.getValue());
            }
        }
        if (name != null && query != null) {
            map.put(name, new NamedQueryEntry(query, isNative));
            log.debug("Registered named query '{}': {}", name, query);
        }
    }

    /** Static string-literal extractor (usable outside {@link SqlVisitor}). */
    private static String extractStringLiteralStatic(Expression expr) {
        if (expr instanceof StringLiteralExpr sle) return sle.getValue();
        String s = expr.toString();
        return (s.startsWith("\"") && s.endsWith("\"")) ? s.substring(1, s.length() - 1) : null;
    }

    // -------------------------------------------------------------------------
    // Public entry point
    // -------------------------------------------------------------------------

    /**
     * Scans the given project path for repository interfaces and service classes,
     * extracting SQL operations from all forms.
     *
     * @param projectPath the root path of the Spring Boot project to analyze
     * @param entityModel the entity model from Phase 1, used for table/column resolution
     * @return a map from method reference (e.g. "OrderRepository.findByUserId") to its list of {@link SqlOperation}s
     */
    public Map<String, List<SqlOperation>> collect(String projectPath, EntityModel entityModel) {
        Map<String, List<SqlOperation>> result = new LinkedHashMap<>();
        List<CompilationUnit> cus = ParseUtils.parseFromDir(projectPath);

        // Pre-scan: build named query map from @NamedQuery / @NamedNativeQuery on entity classes
        Map<String, NamedQueryEntry> namedQueryMap = new LinkedHashMap<>();
        for (CompilationUnit cu : cus) {
            cu.findAll(ClassOrInterfaceDeclaration.class)
                .forEach(cls -> extractNamedQueries(cls, namedQueryMap));
        }
        log.debug("Named query registry: {} entries", namedQueryMap.size());

        for (CompilationUnit cu : cus) {
            cu.accept(new SqlVisitor(entityModel, result, namedQueryMap), null);
        }

        return result;
    }

    // -------------------------------------------------------------------------
    // Visitor
    // -------------------------------------------------------------------------

    private static class SqlVisitor extends VoidVisitorAdapter<Void> {

        private final EntityModel entityModel;
        private final Map<String, List<SqlOperation>> result;
        private final Map<String, NamedQueryEntry> namedQueryMap;

        SqlVisitor(EntityModel entityModel,
                   Map<String, List<SqlOperation>> result,
                   Map<String, NamedQueryEntry> namedQueryMap) {
            this.entityModel   = entityModel;
            this.result        = result;
            this.namedQueryMap = namedQueryMap;
        }

        @Override
        public void visit(ClassOrInterfaceDeclaration cls, Void arg) {
            super.visit(cls, arg);

            String className = cls.getNameAsString();
            boolean isRepo   = isRepository(cls);

            String repoEntityName = null;
            String repoTableName  = null;
            if (isRepo) {
                repoEntityName = extractRepoEntityType(cls);
                if (repoEntityName != null) {
                    repoTableName = entityModel.getEntityToTable().getOrDefault(
                        repoEntityName, repoEntityName.toLowerCase());
                }
            }

            for (MethodDeclaration method : cls.getMethods()) {
                String methodName   = method.getNameAsString();
                String sourceMethod = className + "." + methodName;

                // Form A & B: @Query annotation
                Optional<AnnotationExpr> queryAnnot = method.getAnnotationByName("Query");
                if (queryAnnot.isPresent()) {
                    handleQueryAnnotation(queryAnnot.get(), sourceMethod, repoEntityName);
                    continue;
                }

                // Form C: Spring Data derived query method names (only in repo interfaces)
                if (isRepo && repoTableName != null) {
                    int methodLine = method.getBegin().map(p -> p.line).orElse(0);
                    if (handleDerivedQuery(methodName, sourceMethod, repoTableName, repoEntityName, methodLine)) {
                        continue;
                    }
                }

                // Form D / E / F: method body calls
                if (method.getBody().isPresent()) {
                    method.getBody().get().findAll(MethodCallExpr.class).forEach(call -> {
                        String callName = call.getNameAsString();

                        // Form E — createNamedQuery("BC_READ_...")
                        if (callName.equals("createNamedQuery") && !call.getArguments().isEmpty()) {
                            String queryName = extractStringLiteral(call.getArgument(0));
                            if (queryName != null) {
                                NamedQueryEntry entry = namedQueryMap.get(queryName);
                                if (entry != null) {
                                    int line = call.getBegin().map(p -> p.line).orElse(0); // ← NEW
                                    if (entry.isNative()) handleNativeSql(entry.sql(), sourceMethod, line);
                                    else                  handleJpql(entry.sql(), sourceMethod, line);
                                }
                            }
                            return;
                        }

                        // Form F — EM lifecycle: persist / merge / remove / find
                        //
                        // em.merge(detachedEntity) actually issues TWO SQL statements at
                        // runtime: a SELECT to load the current row state into the
                        // persistence context (acquires a shared row lock), then an
                        // UPDATE at flush (acquires an exclusive row lock — a lock
                        // upgrade). Modeling merge as a single UPDATE collapses that
                        // pair and hides the lock-upgrade pattern from the deadlock
                        // analyzer's hold-and-wait check. We therefore emit BOTH
                        // statements for merge, while persist/remove/find stay as a
                        // single statement (their runtime semantics are single-op).
                        if (EM_LIFECYCLE_METHODS.containsKey(callName) && !call.getArguments().isEmpty()) {
                            String sqlType  = EM_LIFECYCLE_METHODS.get(callName);
                            Expression arg0 = call.getArgument(0);
                            String entityHint = guessEntityName(arg0, method, cls);
                            String table = entityHint != null
                                ? entityModel.getEntityToTable().getOrDefault(entityHint, camelToSnake(entityHint))
                                : "unknown";
                            int line = call.getBegin().map(p -> p.line).orElse(0);
                            List<String> tables = table.equals("unknown") ? List.of() : List.of(table);

                            if ("merge".equals(callName)) {
                                // Implicit SELECT issued by merge to load row state
                                // (acquires a shared row lock).
                                addOperation(sourceMethod, new SqlOperation(
                                    "SELECT",
                                    "SELECT FROM " + table + " WHERE id = ? (via em.merge implicit fetch)",
                                    tables,
                                    "id = ?",
                                    sourceMethod, line));
                                // UPDATE issued at flush to persist merged state
                                // (acquires an exclusive row lock — lock upgrade).
                                addOperation(sourceMethod, new SqlOperation(
                                    "UPDATE",
                                    "UPDATE " + table + " SET ... WHERE id = ? (via em.merge)",
                                    tables,
                                    "id = ?",
                                    sourceMethod, line));
                            } else {
                                String pseudoSql = sqlType + " INTO/FROM " + table + " (via em." + callName + ")";
                                addOperation(sourceMethod, new SqlOperation(
                                    sqlType, pseudoSql,
                                    tables,
                                    null, sourceMethod, line));
                            }
                            return;
                        }

                        // Form D — JdbcTemplate / createQuery / createNativeQuery with inline SQL
                        if ((JDBC_METHODS.contains(callName) || EM_METHODS.contains(callName))
                            && !call.getArguments().isEmpty()) {
                            Expression firstArg = call.getArgument(0);
                            String sql = extractStringLiteral(firstArg);
                            if (sql != null && !sql.isBlank()) {
                                int line = call.getBegin().map(p -> p.line).orElse(0); // ← NEW
                                if (callName.equals("createQuery")) handleJpql(sql, sourceMethod, line);
                                else                                handleNativeSql(sql, sourceMethod, line);
                            }
                        }
                    });
                }
            }
        }

        // -----------------------------------------------------------------
        // Repository detection helpers
        // -----------------------------------------------------------------

        private boolean isRepository(ClassOrInterfaceDeclaration cls) {
            for (AnnotationExpr a : cls.getAnnotations()) {
                if (REPO_ANNOTATIONS.contains(a.getNameAsString())) return true;
            }
            for (ClassOrInterfaceType ext : cls.getExtendedTypes()) {
                if (REPO_SUPERINTERFACES.contains(ext.getNameAsString())) return true;
            }
            for (ClassOrInterfaceType impl : cls.getImplementedTypes()) {
                if (REPO_SUPERINTERFACES.contains(impl.getNameAsString())) return true;
            }
            return false;
        }

        private String extractRepoEntityType(ClassOrInterfaceDeclaration cls) {
            for (ClassOrInterfaceType ext : cls.getExtendedTypes()) {
                if (ext.getTypeArguments().isPresent() && !ext.getTypeArguments().get().isEmpty()) {
                    Type first = ext.getTypeArguments().get().get(0);
                    if (first instanceof ClassOrInterfaceType cit) {
                        return cit.getNameAsString();
                    }
                }
            }
            return null;
        }

        // -----------------------------------------------------------------
        // Form A & B
        // -----------------------------------------------------------------

        private void handleQueryAnnotation(AnnotationExpr annotation, String sourceMethod,
                                           String repoEntityName) {
            String queryValue = null;
            boolean isNative  = false;

            if (annotation instanceof SingleMemberAnnotationExpr single) {
                queryValue = extractStringLiteral(single.getMemberValue());
            } else if (annotation instanceof NormalAnnotationExpr normal) {
                for (MemberValuePair mvp : normal.getPairs()) {
                    if (mvp.getNameAsString().equals("value")) {
                        queryValue = extractStringLiteral(mvp.getValue());
                    } else if (mvp.getNameAsString().equals("nativeQuery")) {
                        isNative = mvp.getValue().toString().equals("true");
                    }
                }
            }

            if (queryValue == null || queryValue.isBlank()) return;

            if (isNative) {
                handleNativeSql(queryValue, sourceMethod);
            } else {
                handleJpql(queryValue, sourceMethod);
            }
        }

        // -----------------------------------------------------------------
        // SQL / JPQL handlers
        // -----------------------------------------------------------------

        private void handleNativeSql(String sql, String sourceMethod, int lineNumber) {
            try {
                String cleanSql = normalizeSql(sql);
                Statement stmt  = CCJSqlParserUtil.parse(cleanSql);
                String type     = getSqlType(stmt);
                List<String> tables = new TablesNamesFinder().getTableList(stmt);
                String where    = extractWhere(stmt);
                addOperation(sourceMethod,
                    new SqlOperation(type, sql, tables, where, sourceMethod, lineNumber)); // ← line
            } catch (Exception e) {
                log.warn("JSQLParser failed for '{}' in {}: {}", sql, sourceMethod, e.getMessage());
                String type = guessSqlType(sql);
                addOperation(sourceMethod,
                    new SqlOperation(type, sql, List.of(), null, sourceMethod, lineNumber));
            }
        }

        private void handleJpql(String jpql, String sourceMethod, int lineNumber) {
            String type = guessSqlType(jpql);
            List<String> tables = new ArrayList<>();
            Pattern entityPattern = Pattern.compile("(?i)(?:UPDATE|FROM|JOIN)\\s+([A-Z][a-zA-Z0-9]*)");
            Matcher m = entityPattern.matcher(jpql);
            while (m.find()) {
                String entityName = m.group(1);
                String table = entityModel.getEntityToTable().get(entityName);
                if (table == null) table = camelToSnake(entityName);
                if (!tables.contains(table)) tables.add(table);
            }
            String where = null;
            Pattern wherePattern = Pattern.compile(
                "(?i)WHERE\\s+(.+?)(?:\\s+ORDER\\s+BY|\\s+GROUP\\s+BY|$)");
            Matcher wm = wherePattern.matcher(jpql);
            if (wm.find()) where = wm.group(1).trim();
            addOperation(sourceMethod,
                new SqlOperation(type, jpql, tables, where, sourceMethod, lineNumber));
        }

        private void handleNativeSql(String sql, String sourceMethod) {
            handleNativeSql(sql, sourceMethod, 0);
        }
        private void handleJpql(String jpql, String sourceMethod) {
            handleJpql(jpql, sourceMethod, 0);
        }

        // -----------------------------------------------------------------
        // Form C — derived query methods
        // -----------------------------------------------------------------

        private boolean handleDerivedQuery(String methodName, String sourceMethod,
                                           String tableName, String entityName, int lineNumber) {
            List<String> simpleFields = new ArrayList<>();
            Map<String, Class<?>> typedFields = new LinkedHashMap<>();

            String prefix = entityName + ".";

            for (String key : entityModel.getFieldToColumn().keySet()) {
                if (key.startsWith(prefix)) {
                    simpleFields.add(key.substring(prefix.length()));
                }
            }

            for (Map.Entry<String, RelationshipInfo> entry : entityModel.getRelationships().entrySet()) {
                String key = entry.getKey();
                if (!key.startsWith(prefix)) continue;

                String relFieldName = key.substring(prefix.length());
                String targetEntity = entry.getValue().getTargetEntity();

                if (targetEntity == null) {
                    simpleFields.add(relFieldName);
                    continue;
                }

                try {
                    Class<?> nestedClass = buildNestedEntityClass(targetEntity);
                    typedFields.put(relFieldName, nestedClass);
                    log.debug("Built nested dynamic class for relationship field '{}.{}' → {}",
                        entityName, relFieldName, targetEntity);
                } catch (Exception e) {
                    log.debug("Could not build nested class for '{}': {} — falling back to Object",
                        targetEntity, e.getMessage());
                    simpleFields.add(relFieldName);
                }
            }

            for (String common : List.of("id", "name", "status", "createdAt", "updatedAt")) {
                if (!simpleFields.contains(common) && !typedFields.containsKey(common)) {
                    simpleFields.add(common);
                }
            }

            try {
                Class<?> dynClass = buildDynamicEntityClass(entityName, simpleFields, typedFields);
                PartTree tree = new PartTree(methodName, dynClass);

                String type;
                if (tree.isDelete()) {
                    type = "DELETE";
                } else if (tree.isCountProjection()) {
                    type = "SELECT COUNT";
                } else {
                    type = "SELECT";
                }

                Map<String, String> tableAliases = new LinkedHashMap<>();
                tableAliases.put(tableName, tableAlias(tableName));

                record PartInfo(String aliasedColumn, String joinedTable, RelationshipInfo rel) {}
                List<PartInfo> partInfos = new ArrayList<>();

                for (Part part : tree.getParts()) {
                    PropertyPath prop = part.getProperty();
                    String leafField  = prop.getLeafProperty().getSegment();

                    if (prop.hasNext()) {
                        String ownerField     = prop.getSegment();
                        String ownerQualified = entityName + "." + ownerField;
                        RelationshipInfo rel  = entityModel.getRelationships().get(ownerQualified);

                        if (rel != null) {
                            String joinedTable = entityModel.getEntityToTable()
                                .getOrDefault(rel.getTargetEntity(), camelToSnake(rel.getTargetEntity()));
                            tableAliases.putIfAbsent(joinedTable, tableAlias(joinedTable));

                            String targetQualified = rel.getTargetEntity() + "." + leafField;
                            String column = entityModel.getFieldToColumn()
                                .getOrDefault(targetQualified, camelToSnake(leafField));
                            String alias = tableAliases.get(joinedTable);
                            partInfos.add(new PartInfo(alias + "." + column, joinedTable, rel));
                        } else {
                            partInfos.add(new PartInfo(camelToSnake(leafField), null, null));
                        }
                    } else {
                        String qualifiedField = entityName + "." + leafField;
                        String column = entityModel.getFieldToColumn()
                            .getOrDefault(qualifiedField, camelToSnake(leafField));
                        String alias = tableAliases.get(tableName);
                        partInfos.add(new PartInfo(alias + "." + column, null, null));
                    }
                }

                String partSeparator = methodName.matches(".*(?:Or)[A-Z].*") ? " OR " : " AND ";

                String ownerAlias = tableAliases.get(tableName);
                StringBuilder fromClause = new StringBuilder(tableName).append(" ").append(ownerAlias);

                Set<String> joinedAlready = new LinkedHashSet<>();
                for (PartInfo pi : partInfos) {
                    if (pi.joinedTable() == null || joinedAlready.contains(pi.joinedTable())) continue;
                    joinedAlready.add(pi.joinedTable());

                    String joinAlias  = tableAliases.get(pi.joinedTable());
                    String joinColumn = pi.rel().getJoinColumn();
                    fromClause.append(" JOIN ").append(pi.joinedTable()).append(" ").append(joinAlias)
                        .append(" ON ").append(joinAlias).append(".id")
                        .append(" = ").append(ownerAlias).append(".").append(joinColumn);
                }

                String whereCondition;
                if (joinedAlready.isEmpty()) {
                    whereCondition = partInfos.isEmpty() ? null :
                        partInfos.stream()
                            .map(pi -> pi.aliasedColumn().contains(".")
                                ? pi.aliasedColumn().substring(pi.aliasedColumn().indexOf('.') + 1)
                                : pi.aliasedColumn())
                            .map(col -> col + " = ?")
                            .collect(Collectors.joining(partSeparator));
                } else {
                    whereCondition = partInfos.isEmpty() ? null :
                        partInfos.stream()
                            .map(pi -> pi.aliasedColumn() + " = ?")
                            .collect(Collectors.joining(partSeparator));
                }

                StringBuilder selectList = new StringBuilder(ownerAlias).append(".*");
                for (String jt : joinedAlready) {
                    selectList.append(", ").append(tableAliases.get(jt)).append(".*");
                }

                List<String> involvedTables = new ArrayList<>(tableAliases.keySet());

                String rawSql;
                if (type.equals("DELETE")) {
                    rawSql = "DELETE FROM " + tableName
                        + (whereCondition != null ? " WHERE " + whereCondition : "");
                } else if (type.equals("SELECT COUNT")) {
                    if (joinedAlready.isEmpty()) {
                        rawSql = "SELECT COUNT(*) FROM " + tableName
                            + (whereCondition != null ? " WHERE " + whereCondition : "");
                    } else {
                        rawSql = "SELECT COUNT(*) FROM " + fromClause
                            + (whereCondition != null ? " WHERE " + whereCondition : "");
                    }
                } else {
                    if (joinedAlready.isEmpty()) {
                        rawSql = "SELECT * FROM " + tableName
                            + (whereCondition != null ? " WHERE " + whereCondition : "");
                    } else {
                        rawSql = "SELECT " + selectList + " FROM " + fromClause
                            + (whereCondition != null ? " WHERE " + whereCondition : "");
                    }
                }

                addOperation(sourceMethod,
                    new SqlOperation(type, rawSql, involvedTables, whereCondition, sourceMethod, lineNumber));
                return true;
            } catch (Exception e) {
                log.debug("PartTree did not recognize '{}' in {}: {}",
                    methodName, sourceMethod, e.getMessage());
                return false;
            }
        }

        private Class<?> buildNestedEntityClass(String targetEntity) {
            List<String> targetFields = new ArrayList<>();
            String prefix = targetEntity + ".";
            for (String key : entityModel.getFieldToColumn().keySet()) {
                if (key.startsWith(prefix)) {
                    targetFields.add(key.substring(prefix.length()));
                }
            }
            for (String common : List.of("id", "name", "status", "createdAt", "updatedAt")) {
                if (!targetFields.contains(common)) targetFields.add(common);
            }
            return buildDynamicEntityClass(targetEntity, targetFields, Collections.emptyMap());
        }

        // -----------------------------------------------------------------
        // Form F helper — resolve entity name from EM lifecycle call argument
        // -----------------------------------------------------------------

        /**
         * Resolves the entity name from an EM lifecycle call argument.
         * <p>Priority order:
         * <ol>
         *   <li>Class literal — {@code em.find(CountryImpl.class, id)}</li>
         *   <li>Local variable type declared in the method body</li>
         *   <li>Field type declared in the enclosing class</li>
         *   <li>Fallback: capitalise the variable name and apply suffix stripping</li>
         * </ol>
         * Each candidate type name is passed through {@link #resolveEntityFromTypeName}
         * which handles {@code Impl}/{@code Dao}/{@code Entity} suffixes common in
         * frameworks like Broadleaf Commerce.
         */
        private String guessEntityName(Expression arg,
                                       MethodDeclaration method,
                                       ClassOrInterfaceDeclaration cls) {
            // 1. Class literal: em.find(CountryImpl.class, id)
            if (arg instanceof ClassExpr ce) {
                return resolveEntityFromTypeName(ce.getType().asString());
            }

            if (!(arg instanceof NameExpr ne)) return null;
            String varName = ne.getNameAsString();

            // 2. Method parameters — Parameter nodes are NOT VariableDeclarators
            String paramType = method.getParameters().stream()
                .filter(p -> p.getNameAsString().equals(varName))
                .map(p -> p.getType().asString())
                .findFirst().orElse(null);
            if (paramType != null) {
                String resolved = resolveEntityFromTypeName(paramType);
                if (resolved != null) return resolved;
            }

            // 3. Local variable declarations in the method body
            String localType = method.findAll(VariableDeclarator.class).stream()
                .filter(vd -> vd.getNameAsString().equals(varName))
                .map(vd -> vd.getType().asString())
                .findFirst().orElse(null);
            if (localType != null) {
                String resolved = resolveEntityFromTypeName(localType);
                if (resolved != null) return resolved;
            }

            // 4. Enclosing class field declarations
            String fieldType = cls.getFields().stream()
                .flatMap(fd -> fd.getVariables().stream())
                .filter(vd -> vd.getNameAsString().equals(varName))
                .map(vd -> vd.getType().asString())
                .findFirst().orElse(null);
            if (fieldType != null) {
                String resolved = resolveEntityFromTypeName(fieldType);
                if (resolved != null) return resolved;
            }

            // 5. Fallback: capitalise variable name and attempt resolution
            String capitalized = Character.toUpperCase(varName.charAt(0)) + varName.substring(1);
            return resolveEntityFromTypeName(capitalized);
        }

        /**
         * Resolves a raw Java type name to the best matching entity name in the entity model.
         * <p>Resolution steps:
         * <ol>
         *   <li>Direct match — e.g. {@code "OrderImpl"} already in entity model</li>
         *   <li>Strip known suffix ({@code Impl}, {@code Dao}, {@code Entity}, {@code Repository})
         *       to get a base name — e.g. {@code "CountryDao"} → {@code "Country"}</li>
         *   <li>Try the stripped base directly — e.g. {@code "Country"} interface is the entity key</li>
         *   <li>Try {@code base + "Impl"} — the most common JPA pattern:
         *       {@code "CountryDao"} → {@code "Country"} → {@code "CountryImpl"} → {@code BLC_COUNTRY}</li>
         *   <li>Return {@code base} as a best-effort fallback so {@code camelToSnake} at least
         *       produces {@code "country"} rather than {@code "country_dao"}</li>
         * </ol>
         * Returns {@code null} if no suffix was stripped and the type is not in the entity model,
         * allowing the caller to fall through to camelToSnake on the original variable name.
         */
        private String resolveEntityFromTypeName(String typeName) {
            if (typeName == null || typeName.isBlank()) return null;
            if (JAVA_TYPES.contains(typeName)) return null;

            // 1. Direct match
            if (entityModel.getEntityToTable().containsKey(typeName)) return typeName;

            // 2. Try typeName + "Impl" — the most common Broadleaf pattern:
            //    interface "OrderMultishipOption" → "OrderMultishipOptionImpl" → BLC_ORDER_MULTISHIP_OPTION
            //    This must run BEFORE suffix stripping so "OrderItem" → "OrderItemImpl" wins
            //    over "OrderItem" → strip nothing → dead end.
            if (entityModel.getEntityToTable().containsKey(typeName + "Impl")) return typeName + "Impl";

            // 3. Strip known suffix to get base name
            String base = typeName;
            for (String suffix : ENTITY_TYPE_SUFFIXES) {
                if (typeName.endsWith(suffix) && typeName.length() > suffix.length()) {
                    base = typeName.substring(0, typeName.length() - suffix.length());
                    break;
                }
            }

            // No suffix was stripped — nothing more we can do
            if (base.equals(typeName)) return null;

            // 4. Try base directly (e.g. "Country" interface is the entity key)
            if (entityModel.getEntityToTable().containsKey(base)) return base;

            // 5. Try base + "Impl"
            if (entityModel.getEntityToTable().containsKey(base + "Impl")) return base + "Impl";

            // 6. Return base as fallback — better than the suffixed original for camelToSnake
            return base;
        }

        // -----------------------------------------------------------------
        // Utility
        // -----------------------------------------------------------------

        private static String camelToSnake(String camel) {
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

        private static String tableAlias(String tableName) {
            StringBuilder sb = new StringBuilder();
            boolean nextChar = true;
            for (char c : tableName.toCharArray()) {
                if (c == '_') {
                    nextChar = true;
                } else if (nextChar) {
                    sb.append(c);
                    nextChar = false;
                }
            }
            return sb.toString();
        }

        private static String extractStringLiteral(Expression expr) {
            if (expr instanceof StringLiteralExpr sle) return sle.getValue();
            String s = expr.toString();
            return (s.startsWith("\"") && s.endsWith("\"")) ? s.substring(1, s.length() - 1) : null;
        }

        private String getSqlType(Statement stmt) {
            if (stmt instanceof Select) return "SELECT";
            if (stmt instanceof Insert) return "INSERT";
            if (stmt instanceof Update) return "UPDATE";
            if (stmt instanceof Delete) return "DELETE";
            return "UNKNOWN";
        }

        private String guessSqlType(String sql) {
            String upper = sql.trim().toUpperCase();
            if (upper.startsWith("SELECT") || upper.startsWith("FROM")) return "SELECT";
            if (upper.startsWith("INSERT")) return "INSERT";
            if (upper.startsWith("UPDATE")) return "UPDATE";
            if (upper.startsWith("DELETE")) return "DELETE";
            return "SELECT";
        }

        private String extractWhere(Statement stmt) {
            try {
                if (stmt instanceof Select sel) {
                    var plainSelect = sel.getPlainSelect();
                    if (plainSelect != null && plainSelect.getWhere() != null) {
                        return plainSelect.getWhere().toString();
                    }
                } else if (stmt instanceof Update upd) {
                    if (upd.getWhere() != null) return upd.getWhere().toString();
                } else if (stmt instanceof Delete del) {
                    if (del.getWhere() != null) return del.getWhere().toString();
                }
            } catch (Exception e) {
                log.debug("Could not extract WHERE: {}", e.getMessage());
            }
            return null;
        }

        private void addOperation(String key, SqlOperation op) {
            result.computeIfAbsent(key, k -> new ArrayList<>()).add(op);
        }
    }

    private static String normalizeSql(String sql) {
        if (sql == null) return null;
        return sql
            // Remove Hibernate schema prefix: {h-schema}TABLE → TABLE
            .replaceAll("\\{h-schema\\}", "")
            // Collapse Java string concatenation artifacts: " + " → space
            .replaceAll("\"\\s*\\+\\s*\"", " ")
            // Normalize whitespace
            .replaceAll("\\s+", " ")
            .trim();
    }
}