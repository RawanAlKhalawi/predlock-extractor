package com.predlock.extractor.phase3;

import com.github.javaparser.ParseProblemException;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.predlock.extractor.model.EntityModel;
import com.predlock.extractor.model.RelationshipInfo;
import com.predlock.extractor.model.SqlOperation;
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
 * JdbcTemplate calls, and EntityManager calls.
 * <p>
 * Handles four forms:
 * <ul>
 *   <li>Form A — {@code @Query} with {@code nativeQuery=true}: parsed with JSQLParser</li>
 *   <li>Form B — {@code @Query} with JPQL: entity names mapped to tables via EntityModel</li>
 *   <li>Form C — Spring Data derived query method names: parsed via naming convention</li>
 *   <li>Form D — JdbcTemplate / EntityManager string arguments: parsed with JSQLParser</li>
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

    private static final Pattern CAMEL_SPLIT = Pattern.compile("(?<=[a-z])(?=[A-Z])");

    // -------------------------------------------------------------------------
    // FIX 1: Use a single shared DynClassLoader so that nested entity classes
    // defined for relationship fields are resolvable when PartTree reflects on
    // the parent entity class.  The original code created a new loader per
    // class, so any reference from parent → nested class crossed loader
    // boundaries and caused NoClassDefFoundError / property-not-found errors.
    // -------------------------------------------------------------------------

    /**
     * Custom ClassLoader for defining dynamic entity classes at runtime.
     * A single shared instance is used so that nested classes (e.g. the
     * dynamic "Product" class referenced by "OrderItem.product") are visible
     * to the parent class's loader during PartTree reflection.
     */
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
    // FIX 2: buildDynamicEntityClass now accepts a Map<String, Class<?>> for
    // relationship fields that must carry a specific return type so that
    // PartTree can traverse nested property paths (e.g. product_Id →
    // getProduct().getId()).  Simple scalar fields continue to use Object.
    // -------------------------------------------------------------------------

    /**
     * Builds a dynamic class with getter/setter properties for the given fields,
     * suitable for use with Spring Data's {@link PartTree}.
     *
     * @param entityName   simple entity name, used only for the generated class name
     * @param simpleFields fields whose getter/setter type is {@code Object}
     * @param typedFields  relationship fields mapped to their target dynamic class;
     *                     getters will declare the exact return type so PartTree can
     *                     traverse nested paths (e.g. {@code product_Id})
     */
    private static Class<?> buildDynamicEntityClass(String entityName,
                                                    List<String> simpleFields,
                                                    Map<String, Class<?>> typedFields) {
        String className = "com.predlock.dyn." + entityName + "_" + (dynClassCounter++);
        String internalName = className.replace('.', '/');
        // COMPUTE_FRAMES required: without it the JVM 17 verifier rejects the setter bytecode
        // when the field descriptor is a concrete type but the parameter is declared as Object.
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

        // Simple (scalar) fields — getter returns Object
        for (String field : simpleFields) {
            emitFieldWithAccessors(cw, internalName, field, "Ljava/lang/Object;", "()Ljava/lang/Object;");
        }

        // Typed (relationship) fields — getter returns the concrete nested class
        for (Map.Entry<String, Class<?>> entry : typedFields.entrySet()) {
            String field      = entry.getKey();
            Class<?> type     = entry.getValue();
            String descriptor = "L" + type.getName().replace('.', '/') + ";";
            emitFieldWithAccessors(cw, internalName, field, descriptor, "()" + descriptor);
        }

        cw.visitEnd();
        return SHARED_LOADER.define(className, cw.toByteArray());
    }

    /**
     * Emits a private field plus a public getter and setter into {@code cw}.
     *
     * @param fieldDescriptor  JVM type descriptor for the field storage type (e.g. {@code Ljava/lang/Object;})
     * @param getterDescriptor JVM method descriptor for the getter (e.g. {@code ()Ljava/lang/Object;})
     */
    private static void emitFieldWithAccessors(ClassWriter cw, String ownerInternal,
                                               String fieldName,
                                               String fieldDescriptor,
                                               String getterDescriptor) {
        // Field
        cw.visitField(Opcodes.ACC_PRIVATE, fieldName, fieldDescriptor, null, null).visitEnd();

        String cap = Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);

        // Getter
        MethodVisitor gmv = cw.visitMethod(Opcodes.ACC_PUBLIC, "get" + cap, getterDescriptor, null, null);
        gmv.visitCode();
        gmv.visitVarInsn(Opcodes.ALOAD, 0);
        gmv.visitFieldInsn(Opcodes.GETFIELD, ownerInternal, fieldName, fieldDescriptor);
        gmv.visitInsn(Opcodes.ARETURN);
        gmv.visitMaxs(0, 0);
        gmv.visitEnd();

        // Setter — parameter is Object; CHECKCAST to the field's concrete type before PUTFIELD
        // so the JVM 17 verifier accepts the store even when fieldDescriptor is not Object.
        MethodVisitor smv = cw.visitMethod(Opcodes.ACC_PUBLIC, "set" + cap, "(Ljava/lang/Object;)V", null, null);
        smv.visitCode();
        smv.visitVarInsn(Opcodes.ALOAD, 0);
        smv.visitVarInsn(Opcodes.ALOAD, 1);
        // Strip leading 'L' and trailing ';' to get the internal name for CHECKCAST
        if (!fieldDescriptor.equals("Ljava/lang/Object;")) {
            String castTarget = fieldDescriptor.substring(1, fieldDescriptor.length() - 1);
            smv.visitTypeInsn(Opcodes.CHECKCAST, castTarget);
        }
        smv.visitFieldInsn(Opcodes.PUTFIELD, ownerInternal, fieldName, fieldDescriptor);
        smv.visitInsn(Opcodes.RETURN);
        smv.visitMaxs(0, 0);
        smv.visitEnd();
    }

    /**
     * Scans the given project path for repository interfaces and service classes,
     * extracting SQL operations from all four forms.
     *
     * @param projectPath the root path of the Spring Boot project to analyze
     * @param entityModel the entity model from Phase 1, used for table/column resolution
     * @return a map from method reference (e.g. "OrderRepository.findByUserId") to its list of {@link SqlOperation}s
     */
    public Map<String, List<SqlOperation>> collect(String projectPath, EntityModel entityModel) {
        Map<String, List<SqlOperation>> result = new LinkedHashMap<>();

        List<CompilationUnit> cus = parseFromDir(projectPath);
        for (CompilationUnit cu : cus) {
            cu.accept(new SqlVisitor(entityModel, result), null);
        }

        return result;
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
                    results.add(StaticJavaParser.parse(file));
                } catch (ParseProblemException e) {
                    log.warn("Parse error in {}: {}", file.getPath(), e.getMessage());
                } catch (Exception e) {
                    log.warn("Error reading {}: {}", file.getPath(), e.getMessage());
                }
            }
        }
    }

    /**
     * Visitor that processes both repository interfaces and service/component classes.
     */
    private static class SqlVisitor extends VoidVisitorAdapter<Void> {

        private final EntityModel entityModel;
        private final Map<String, List<SqlOperation>> result;

        SqlVisitor(EntityModel entityModel, Map<String, List<SqlOperation>> result) {
            this.entityModel = entityModel;
            this.result = result;
        }

        @Override
        public void visit(ClassOrInterfaceDeclaration cls, Void arg) {
            super.visit(cls, arg);

            String className = cls.getNameAsString();

            boolean isRepo = isRepository(cls);

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
                String methodName  = method.getNameAsString();
                String sourceMethod = className + "." + methodName;

                // Form A & B: @Query annotation
                Optional<AnnotationExpr> queryAnnot = method.getAnnotationByName("Query");
                if (queryAnnot.isPresent()) {
                    handleQueryAnnotation(queryAnnot.get(), sourceMethod, repoEntityName);
                    continue;
                }

                // Form C: Spring Data derived query method names (only in repo interfaces)
                if (isRepo && repoTableName != null) {
                    if (handleDerivedQuery(methodName, sourceMethod, repoTableName, repoEntityName)) {
                        continue;
                    }
                }

                // Form D: JdbcTemplate / EntityManager calls inside method body
                if (method.getBody().isPresent()) {
                    method.getBody().get().findAll(MethodCallExpr.class).forEach(call -> {
                        String callName = call.getNameAsString();
                        if ((JDBC_METHODS.contains(callName) || EM_METHODS.contains(callName))
                            && !call.getArguments().isEmpty()) {
                            Expression firstArg = call.getArgument(0);
                            String sql = extractStringLiteral(firstArg);
                            if (sql != null && !sql.isBlank()) {
                                if (callName.equals("createQuery")) {
                                    handleJpql(sql, sourceMethod);
                                } else {
                                    handleNativeSql(sql, sourceMethod);
                                }
                            }
                        }
                    });
                }
            }
        }

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

        private void handleQueryAnnotation(AnnotationExpr annotation, String sourceMethod, String repoEntityName) {
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

        private void handleNativeSql(String sql, String sourceMethod) {
            try {
                Statement stmt   = CCJSqlParserUtil.parse(sql);
                String type      = getSqlType(stmt);
                List<String> tables = new TablesNamesFinder().getTableList(stmt);
                String where     = extractWhere(stmt);
                addOperation(sourceMethod, new SqlOperation(type, sql, tables, where, sourceMethod));
            } catch (Exception e) {
                log.warn("JSQLParser failed for '{}' in {}: {}", sql, sourceMethod, e.getMessage());
                String type = guessSqlType(sql);
                addOperation(sourceMethod, new SqlOperation(type, sql, List.of(), null, sourceMethod));
            }
        }

        private void handleJpql(String jpql, String sourceMethod) {
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
            Pattern wherePattern = Pattern.compile("(?i)WHERE\\s+(.+?)(?:\\s+ORDER\\s+BY|\\s+GROUP\\s+BY|$)");
            Matcher wm = wherePattern.matcher(jpql);
            if (wm.find()) where = wm.group(1).trim();
            addOperation(sourceMethod, new SqlOperation(type, jpql, tables, where, sourceMethod));
        }

        private boolean handleDerivedQuery(String methodName, String sourceMethod,
                                           String tableName, String entityName) {
            List<String> simpleFields = new ArrayList<>();
            Map<String, Class<?>> typedFields = new LinkedHashMap<>();

            String prefix = entityName + ".";

            // Scalar fields from field-to-column map
            for (String key : entityModel.getFieldToColumn().keySet()) {
                if (key.startsWith(prefix)) {
                    simpleFields.add(key.substring(prefix.length()));
                }
            }

            // Relationship fields — build a nested dynamic class per target entity
            for (Map.Entry<String, RelationshipInfo> entry : entityModel.getRelationships().entrySet()) {
                String key = entry.getKey();
                if (!key.startsWith(prefix)) continue;

                String relFieldName = key.substring(prefix.length());
                String targetEntity = entry.getValue().getTargetEntity();

                if (targetEntity == null) {
                    // Can't determine target entity — treat as a plain Object field
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

            // Ensure common fields are present on the entity class itself
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

                // alias map: table name → short alias (e.g. "order_item" → "oi", "product" → "p")
                // Built lazily as tables are encountered; owner table is always first.
                Map<String, String> tableAliases = new LinkedHashMap<>();
                tableAliases.put(tableName, tableAlias(tableName));

                // Per-Part result: (aliasedColumn, joinedTable, RelationshipInfo)
                // joinedTable/rel are null for simple (non-nested) fields.
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

                // Build the FROM + JOIN clause
                String ownerAlias = tableAliases.get(tableName);
                StringBuilder fromClause = new StringBuilder(tableName).append(" ").append(ownerAlias);

                // Collect unique joins (one JOIN per distinct joined table)
                Set<String> joinedAlready = new LinkedHashSet<>();
                for (PartInfo pi : partInfos) {
                    if (pi.joinedTable() == null || joinedAlready.contains(pi.joinedTable())) continue;
                    joinedAlready.add(pi.joinedTable());

                    String joinAlias    = tableAliases.get(pi.joinedTable());
                    String joinColumn   = pi.rel().getJoinColumn(); // e.g. "product_id"
                    // ON clause: joinedTable.id = ownerTable.joinColumn
                    fromClause.append(" JOIN ").append(pi.joinedTable()).append(" ").append(joinAlias)
                        .append(" ON ").append(joinAlias).append(".id")
                        .append(" = ").append(ownerAlias).append(".").append(joinColumn);
                }

                // If no JOINs, strip aliases from WHERE columns — use plain column name only
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

                // Select list: owner alias.* plus each joined alias.*
                StringBuilder selectList = new StringBuilder(ownerAlias).append(".*");
                for (String jt : joinedAlready) {
                    selectList.append(", ").append(tableAliases.get(jt)).append(".*");
                }

                List<String> involvedTables = new ArrayList<>(tableAliases.keySet());

                String rawSql;
                if (type.equals("DELETE")) {
                    rawSql = "DELETE FROM " + tableName + (whereCondition != null ? " WHERE " + whereCondition : "");
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

                addOperation(sourceMethod, new SqlOperation(type, rawSql, involvedTables, whereCondition, sourceMethod));
                return true;
            } catch (Exception e) {
                log.debug("PartTree did not recognize '{}' in {}: {}", methodName, sourceMethod, e.getMessage());
                return false;
            }
        }

        /**
         * Builds a lightweight dynamic class for a target relationship entity.
         * The class exposes the target entity's scalar fields (from EntityModel)
         * plus the common fallback fields so that PartTree can resolve nested
         * property paths such as {@code product_Id} or {@code product_Name}.
         */
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
            // Nested classes have no further typed relationships — pass empty map
            return buildDynamicEntityClass(targetEntity, targetFields, Collections.emptyMap());
        }

        private String buildWhereCondition(List<String> columns) {
            if (columns.isEmpty()) return null;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) sb.append(" AND ");
                sb.append(columns.get(i)).append(" = ?");
            }
            return sb.toString();
        }

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

        /**
         * Derives a short SQL alias from a table name.
         * Joins the first letter of each underscore-separated word.
         * E.g. "order_item" → "oi", "product" → "p", "user_transaction" → "ut"
         */
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
            if (expr instanceof StringLiteralExpr sle) {
                return sle.getValue();
            }
            String s = expr.toString();
            if (s.startsWith("\"") && s.endsWith("\"")) {
                return s.substring(1, s.length() - 1);
            }
            return null;
        }

        private String getSqlType(Statement stmt) {
            if (stmt instanceof Select)  return "SELECT";
            if (stmt instanceof Insert)  return "INSERT";
            if (stmt instanceof Update)  return "UPDATE";
            if (stmt instanceof Delete)  return "DELETE";
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
}