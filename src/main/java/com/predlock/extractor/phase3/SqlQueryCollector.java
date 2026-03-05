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
import org.springframework.data.repository.query.parser.Part;
import org.springframework.data.repository.query.parser.PartTree;

import java.io.File;
import java.util.*;
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

    /**
     * Custom ClassLoader for defining dynamic entity classes at runtime.
     */
    private static class DynClassLoader extends ClassLoader {
        DynClassLoader(ClassLoader parent) { super(parent); }
        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    private static int dynClassCounter = 0;

    /**
     * Builds a dynamic class with getter/setter properties for the given field names,
     * suitable for use with Spring Data's {@link PartTree}.
     */
    private static Class<?> buildDynamicEntityClass(String entityName, List<String> fieldNames) {
        String className = "com.predlock.dyn." + entityName + "_" + (dynClassCounter++);
        String internalName = className.replace('.', '/');
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);

        // Default constructor
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();

        for (String field : fieldNames) {
            cw.visitField(Opcodes.ACC_PRIVATE, field, "Ljava/lang/Object;", null, null).visitEnd();

            String cap = Character.toUpperCase(field.charAt(0)) + field.substring(1);

            MethodVisitor gmv = cw.visitMethod(Opcodes.ACC_PUBLIC, "get" + cap, "()Ljava/lang/Object;", null, null);
            gmv.visitCode();
            gmv.visitVarInsn(Opcodes.ALOAD, 0);
            gmv.visitFieldInsn(Opcodes.GETFIELD, internalName, field, "Ljava/lang/Object;");
            gmv.visitInsn(Opcodes.ARETURN);
            gmv.visitMaxs(1, 1);
            gmv.visitEnd();

            MethodVisitor smv = cw.visitMethod(Opcodes.ACC_PUBLIC, "set" + cap, "(Ljava/lang/Object;)V", null, null);
            smv.visitCode();
            smv.visitVarInsn(Opcodes.ALOAD, 0);
            smv.visitVarInsn(Opcodes.ALOAD, 1);
            smv.visitFieldInsn(Opcodes.PUTFIELD, internalName, field, "Ljava/lang/Object;");
            smv.visitInsn(Opcodes.RETURN);
            smv.visitMaxs(2, 2);
            smv.visitEnd();
        }

        cw.visitEnd();
        return new DynClassLoader(SqlQueryCollector.class.getClassLoader()).define(className, cw.toByteArray());
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

            // Determine if this is a Spring Data repository interface
            boolean isRepo = isRepository(cls);

            // Determine the entity type for this repository (from extends generic arg)
            String repoEntityName = null;
            String repoTableName = null;
            if (isRepo) {
                repoEntityName = extractRepoEntityType(cls);
                if (repoEntityName != null) {
                    repoTableName = entityModel.getEntityToTable().getOrDefault(
                            repoEntityName, repoEntityName.toLowerCase());
                }
            }

            for (MethodDeclaration method : cls.getMethods()) {
                String methodName = method.getNameAsString();
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
                                // If it's an EntityManager JPQL query (createQuery, not createNativeQuery)
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

        /**
         * Check if a class/interface is a Spring Data repository.
         */
        private boolean isRepository(ClassOrInterfaceDeclaration cls) {
            // Check for @Repository annotation
            for (AnnotationExpr a : cls.getAnnotations()) {
                if (REPO_ANNOTATIONS.contains(a.getNameAsString())) return true;
            }
            // Check if it extends a known repository interface
            for (ClassOrInterfaceType ext : cls.getExtendedTypes()) {
                if (REPO_SUPERINTERFACES.contains(ext.getNameAsString())) return true;
            }
            for (ClassOrInterfaceType impl : cls.getImplementedTypes()) {
                if (REPO_SUPERINTERFACES.contains(impl.getNameAsString())) return true;
            }
            return false;
        }

        /**
         * Extract the entity type from repository generic parameter, e.g.
         * {@code JpaRepository<Order, Long>} → "Order"
         */
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

        /**
         * Handle @Query annotation (Form A: native, Form B: JPQL).
         */
        private void handleQueryAnnotation(AnnotationExpr annotation, String sourceMethod, String repoEntityName) {
            String queryValue = null;
            boolean isNative = false;

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

        /**
         * Parse native SQL with JSQLParser (Tool A).
         */
        private void handleNativeSql(String sql, String sourceMethod) {
            try {
                Statement stmt = CCJSqlParserUtil.parse(sql);
                String type = getSqlType(stmt);
                List<String> tables = new TablesNamesFinder().getTableList(stmt);
                String where = extractWhere(stmt);

                addOperation(sourceMethod, new SqlOperation(type, sql, tables, where, sourceMethod));
            } catch (Exception e) {
                log.warn("JSQLParser failed for '{}' in {}: {}", sql, sourceMethod, e.getMessage());
                // Fallback: try to determine type from SQL string
                String type = guessSqlType(sql);
                addOperation(sourceMethod, new SqlOperation(type, sql, List.of(), null, sourceMethod));
            }
        }

        /**
         * Handle JPQL query (Tool B): map entity names to table names using EntityModel.
         */
        private void handleJpql(String jpql, String sourceMethod) {
            String type = guessSqlType(jpql);

            // Extract entity names from JPQL and map to tables
            List<String> tables = new ArrayList<>();
            // Pattern: UPDATE/FROM/JOIN EntityName
            Pattern entityPattern = Pattern.compile("(?i)(?:UPDATE|FROM|JOIN)\\s+([A-Z][a-zA-Z0-9]*)");
            Matcher m = entityPattern.matcher(jpql);
            while (m.find()) {
                String entityName = m.group(1);
                String table = entityModel.getEntityToTable().get(entityName);
                if (table == null) {
                    // Fallback: convert class name to snake_case
                    table = camelToSnake(entityName);
                }
                if (!tables.contains(table)) {
                    tables.add(table);
                }
            }

            // Extract WHERE condition from JPQL
            String where = null;
            Pattern wherePattern = Pattern.compile("(?i)WHERE\\s+(.+?)(?:\\s+ORDER\\s+BY|\\s+GROUP\\s+BY|$)");
            Matcher wm = wherePattern.matcher(jpql);
            if (wm.find()) {
                where = wm.group(1).trim();
            }

            // Reconstruct a pseudo-SQL for rawSql
            String rawSql = jpql;

            addOperation(sourceMethod, new SqlOperation(type, rawSql, tables, where, sourceMethod));
        }

        /**
         * Handle Spring Data derived query method names (Form C) using PartTree.
         * Returns true if the method was recognized as a derived query, false otherwise.
         */
        private boolean handleDerivedQuery(String methodName, String sourceMethod,
                                           String tableName, String entityName) {
            // Collect known field names for this entity from EntityModel
            List<String> fieldNames = new ArrayList<>();
            String prefix = entityName + ".";
            for (String key : entityModel.getFieldToColumn().keySet()) {
                if (key.startsWith(prefix)) {
                    fieldNames.add(key.substring(prefix.length()));
                }
            }
            // Also add fields from relationships
            for (String key : entityModel.getRelationships().keySet()) {
                if (key.startsWith(prefix)) {
                    fieldNames.add(key.substring(prefix.length()));
                }
            }
            // Ensure common fields are present
            for (String common : List.of("id", "name", "status", "createdAt", "updatedAt")) {
                if (!fieldNames.contains(common)) fieldNames.add(common);
            }

            try {
                Class<?> dynClass = buildDynamicEntityClass(entityName, fieldNames);
                PartTree tree = new PartTree(methodName, dynClass);

                String type;
                if (tree.isDelete()) {
                    type = "DELETE";
                } else if (tree.isCountProjection()) {
                    type = "SELECT COUNT";
                } else {
                    type = "SELECT";
                }

                // Extract WHERE columns from PartTree parts
                List<String> columns = new ArrayList<>();
                for (Part part : tree.getParts()) {
                    String fieldName = part.getProperty().getSegment();
                    String qualifiedField = entityName + "." + fieldName;
                    String column = entityModel.getFieldToColumn().getOrDefault(qualifiedField, camelToSnake(fieldName));
                    columns.add(column);
                }
                String whereCondition = buildWhereCondition(columns);

                String rawSql;
                if (type.equals("DELETE")) {
                    rawSql = "DELETE FROM " + tableName + (whereCondition != null ? " WHERE " + whereCondition : "");
                } else if (type.equals("SELECT COUNT")) {
                    rawSql = "SELECT COUNT(*) FROM " + tableName + (whereCondition != null ? " WHERE " + whereCondition : "");
                } else {
                    rawSql = "SELECT * FROM " + tableName + (whereCondition != null ? " WHERE " + whereCondition : "");
                }

                addOperation(sourceMethod, new SqlOperation(type, rawSql, List.of(tableName), whereCondition, sourceMethod));
                return true;
            } catch (Exception e) {
                log.debug("PartTree did not recognize '{}' in {}: {}", methodName, sourceMethod, e.getMessage());
                return false;
            }
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
         * Extract a string literal from an expression, stripping quotes.
         */
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
}
