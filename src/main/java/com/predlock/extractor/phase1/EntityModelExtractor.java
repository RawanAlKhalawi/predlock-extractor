package com.predlock.extractor.phase1;

import com.github.javaparser.ParseProblemException;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.predlock.extractor.model.EntityModel;
import com.predlock.extractor.model.RelationshipInfo;
import com.predlock.extractor.util.ParseUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;

/**
 * Phase 1: Extracts the JPA entity model from Spring Boot source code.
 * <p>
 * Adapted from HBSniff's EntityParser annotation-reading logic.
 * Uses JavaParser {@link VoidVisitorAdapter} to walk all {@code .java} files
 * and extract {@code @Entity}, {@code @Table}, {@code @Column},
 * relationship annotations, and fetch types.
 */
public class EntityModelExtractor {

    private static final Logger log = LoggerFactory.getLogger(EntityModelExtractor.class);

    // Annotation constants (adapted from HBSniff Const.java)
    private static final String ENTITY_ANNOT = "Entity";
    private static final String TABLE_ANNOT = "Table";
    private static final Set<String> TABLE_ANNOT_QUALIFIED = Set.of(
        "Table",
        "javax.persistence.Table",
        "jakarta.persistence.Table"
    );
    private static final String COLUMN_ANNOT = "Column";
    private static final String JOIN_COLUMN_ANNOT = "JoinColumn";
    private static final String ONE_TO_MANY_ANNOT = "OneToMany";
    private static final String MANY_TO_ONE_ANNOT = "ManyToOne";
    private static final String MANY_TO_MANY_ANNOT = "ManyToMany";
    private static final String ONE_TO_ONE_ANNOT = "OneToOne";
    private static final String FETCH_ATTR = "fetch";
    private static final String NAME_ATTR = "name";
    private static final String EAGER = "EAGER";
    private static final String LAZY = "LAZY";

    private static final Set<String> RELATIONSHIP_ANNOTS = Set.of(
            ONE_TO_MANY_ANNOT, MANY_TO_ONE_ANNOT, MANY_TO_MANY_ANNOT, ONE_TO_ONE_ANNOT
    );

    /**
     * Scans the given project path for JPA entity classes and extracts
     * entity-to-table mappings, field-to-column mappings, and relationships.
     *
     * @param projectPath the root path of the Spring Boot project to analyze
     * @return the extracted {@link EntityModel}
     */
    public EntityModel extract(String projectPath) {
        EntityModel model = new EntityModel();

        // Parse all .java files (adapted from HBSniff EntityParser.parseFromDir)
        List<CompilationUnit> cus = ParseUtils.parseFromDir(projectPath);

        // Filter to @Entity classes (adapted from HBSniff EntityParser.getEntities)
        // and visit each one
        for (CompilationUnit cu : cus) {
            cu.accept(new EntityVisitor(model), null);
        }

        return model;
    }

    /**
     * VoidVisitorAdapter that visits class declarations and extracts JPA metadata.
     * Adapted from HBSniff's annotation-reading pattern using findAll(AnnotationExpr.class)
     * and MemberValuePair inspection.
     */
    private static class EntityVisitor extends VoidVisitorAdapter<Void> {

        private final EntityModel model;

        EntityVisitor(EntityModel model) {
            this.model = model;
        }

        @Override
        public void visit(ClassOrInterfaceDeclaration cls, Void arg) {
            super.visit(cls, arg);

            // Check if class has @Entity annotation (adapted from HBSniff getEntities)
            boolean isEntity = false;
            for (AnnotationExpr annotation : cls.getAnnotations()) {
                if (annotation.getNameAsString().equals(ENTITY_ANNOT)) {
                    isEntity = true;
                    break;
                }
            }
            if (!isEntity) {
                return;
            }

            String className = cls.getNameAsString();

            // Extract @Table(name=...) or default to lowercase class name
            String tableName = className.toLowerCase();
            for (AnnotationExpr annotation : cls.getAnnotations()) {
                String annotSimpleName = annotation.getNameAsString();
                // Handle both "Table" and "javax.persistence.Table"
                // but NOT "org.hibernate.annotations.Table" (which uses appliesTo, not name)
                if (!TABLE_ANNOT_QUALIFIED.contains(annotSimpleName)) continue;

                String name = getAnnotationAttribute(annotation, NAME_ATTR);
                if (name != null && !name.isBlank()) {
                    tableName = name;
                    break;   // found the JPA @Table with a real name — done
                }
                // name was null (e.g. Hibernate @Table with only appliesTo) — keep scanning
            }
            model.getEntityToTable().put(className, tableName);

            // Process fields
            for (FieldDeclaration field : cls.getFields()) {
                for (VariableDeclarator var : field.getVariables()) {
                    String fieldName = var.getNameAsString();
                    String qualifiedField = className + "." + fieldName;

                    // Check for relationship annotations
                    // Adapted from HBSniff Fetch/OneByOne detector pattern:
                    // iterate annotations, check name, read MemberValuePair for fetch
                    String relAnnot = null;
                    for (AnnotationExpr annotation : field.getAnnotations()) {
                        if (RELATIONSHIP_ANNOTS.contains(annotation.getNameAsString())) {
                            relAnnot = annotation.getNameAsString();
                            break;
                        }
                    }

                    if (relAnnot != null) {
                        // Build RelationshipInfo
                        String relationType = toRelationType(relAnnot);

                        // Read fetch type (adapted from HBSniff: check MemberValuePair named "fetch")
                        String fetchType = getDefaultFetchType(relAnnot);
                        for (AnnotationExpr annotation : field.getAnnotations()) {
                            if (annotation.getNameAsString().equals(relAnnot)) {
                                String fetchVal = getAnnotationAttribute(annotation, FETCH_ATTR);
                                if (fetchVal != null) {
                                    if (fetchVal.contains(EAGER)) {
                                        fetchType = EAGER;
                                    } else if (fetchVal.contains(LAZY)) {
                                        fetchType = LAZY;
                                    }
                                }
                                break;
                            }
                        }

                        // Read @JoinColumn(name=...)
                        String joinColumn = null;
                        for (AnnotationExpr annotation : field.getAnnotations()) {
                            if (annotation.getNameAsString().equals(JOIN_COLUMN_ANNOT)) {
                                joinColumn = getAnnotationAttribute(annotation, NAME_ATTR);
                                break;
                            }
                        }

                        // Determine target entity from field type
                        String targetEntity = extractTargetEntity(var.getType());

                        model.getRelationships().put(qualifiedField,
                                new RelationshipInfo(targetEntity, joinColumn, fetchType, relationType));
                    } else {
                        // Regular field: extract @Column(name=...) or use field name as-is
                        String columnName = fieldName;
                        for (AnnotationExpr annotation : field.getAnnotations()) {
                            if (annotation.getNameAsString().equals(COLUMN_ANNOT)) {
                                String name = getAnnotationAttribute(annotation, NAME_ATTR);
                                if (name != null) {
                                    columnName = name;
                                }
                                break;
                            }
                        }
                        model.getFieldToColumn().put(qualifiedField, columnName);
                    }
                }
            }
        }
    }

    /**
     * Extract a named attribute value from an annotation.
     * Adapted from HBSniff's MemberValuePair reading pattern.
     */
    private static String getAnnotationAttribute(AnnotationExpr annotation, String attrName) {
        if (annotation instanceof NormalAnnotationExpr normal) {
            for (MemberValuePair mvp : normal.getPairs()) {
                if (mvp.getNameAsString().equals(attrName)) {
                    String val = mvp.getValue().toString();
                    // Strip quotes if present
                    if (val.startsWith("\"") && val.endsWith("\"")) {
                        val = val.substring(1, val.length() - 1);
                    }
                    return val;
                }
            }
        } else if (annotation instanceof SingleMemberAnnotationExpr single) {
            // Single-member annotation: @Table("orders") maps to "value" attr
            if (attrName.equals("value") || attrName.equals(NAME_ATTR)) {
                String val = single.getMemberValue().toString();
                if (val.startsWith("\"") && val.endsWith("\"")) {
                    val = val.substring(1, val.length() - 1);
                }
                return val;
            }
        }
        return null;
    }

    /**
     * Convert annotation name to relation type constant.
     */
    private static String toRelationType(String annotName) {
        return switch (annotName) {
            case ONE_TO_MANY_ANNOT -> "ONE_TO_MANY";
            case MANY_TO_ONE_ANNOT -> "MANY_TO_ONE";
            case MANY_TO_MANY_ANNOT -> "MANY_TO_MANY";
            case ONE_TO_ONE_ANNOT -> "ONE_TO_ONE";
            default -> annotName;
        };
    }

    /**
     * Return the JPA default fetch type for a relationship annotation.
     * @XxxToOne defaults to EAGER; @XxxToMany defaults to LAZY.
     */
    private static String getDefaultFetchType(String annotName) {
        return switch (annotName) {
            case ONE_TO_MANY_ANNOT, MANY_TO_MANY_ANNOT -> LAZY;
            case MANY_TO_ONE_ANNOT, ONE_TO_ONE_ANNOT -> EAGER;
            default -> LAZY;
        };
    }

    /**
     * Extract the target entity name from a field type.
     * Handles generic collections like {@code List<OrderItem>} by extracting the type argument.
     * Adapted from HBSniff Utils.extractTypeFromCollection.
     */
    private static String extractTargetEntity(Type type) {
        if (type instanceof ClassOrInterfaceType cit) {
            Optional<com.github.javaparser.ast.NodeList<Type>> typeArgs = cit.getTypeArguments();
            if (typeArgs.isPresent() && !typeArgs.get().isEmpty()) {
                // e.g. List<OrderItem> → "OrderItem"
                Type inner = typeArgs.get().get(0);
                if (inner instanceof ClassOrInterfaceType innerCit) {
                    return innerCit.getNameAsString();
                }
                return inner.asString();
            }
            return cit.getNameAsString();
        }
        return type.asString();
    }
}
