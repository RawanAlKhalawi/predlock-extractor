package com.predlock.extractor.model;

import java.util.HashMap;
import java.util.Map;

/**
 * Output of Phase 1. Represents the complete entity model extracted from JPA-annotated
 * source code, including entity-to-table mappings, field-to-column mappings, and relationships.
 */
public class EntityModel {

    /** key = simple class name (e.g. "Order"), value = SQL table name (e.g. "orders") */
    private Map<String, String> entityToTable = new HashMap<>();

    /** key = "ClassName.fieldName" (e.g. "Order.userId"), value = SQL column name (e.g. "user_id") */
    private Map<String, String> fieldToColumn = new HashMap<>();

    /** key = "ClassName.fieldName" (e.g. "Order.items"), value = RelationshipInfo */
    private Map<String, RelationshipInfo> relationships = new HashMap<>();

    public EntityModel() {
    }

    public Map<String, String> getEntityToTable() {
        return entityToTable;
    }

    public void setEntityToTable(Map<String, String> entityToTable) {
        this.entityToTable = entityToTable;
    }

    public Map<String, String> getFieldToColumn() {
        return fieldToColumn;
    }

    public void setFieldToColumn(Map<String, String> fieldToColumn) {
        this.fieldToColumn = fieldToColumn;
    }

    public Map<String, RelationshipInfo> getRelationships() {
        return relationships;
    }

    public void setRelationships(Map<String, RelationshipInfo> relationships) {
        this.relationships = relationships;
    }
}
