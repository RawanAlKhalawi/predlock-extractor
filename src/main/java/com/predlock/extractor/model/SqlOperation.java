package com.predlock.extractor.model;

import java.util.List;

/**
 * Represents a single SQL operation extracted from source code (Phase 3 and 4).
 * Contains the operation type, raw SQL, target tables, WHERE condition,
 * and the originating method reference.
 */
public class SqlOperation {

    private String type;
    private String rawSql;
    private List<String> tables;
    private String whereCondition;
    private String sourceMethod;

    public SqlOperation() {
    }

    public SqlOperation(String type, String rawSql, List<String> tables, String whereCondition, String sourceMethod) {
        this.type = type;
        this.rawSql = rawSql;
        this.tables = tables;
        this.whereCondition = whereCondition;
        this.sourceMethod = sourceMethod;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getRawSql() {
        return rawSql;
    }

    public void setRawSql(String rawSql) {
        this.rawSql = rawSql;
    }

    public List<String> getTables() {
        return tables;
    }

    public void setTables(List<String> tables) {
        this.tables = tables;
    }

    public String getWhereCondition() {
        return whereCondition;
    }

    public void setWhereCondition(String whereCondition) {
        this.whereCondition = whereCondition;
    }

    public String getSourceMethod() {
        return sourceMethod;
    }

    public void setSourceMethod(String sourceMethod) {
        this.sourceMethod = sourceMethod;
    }
}
