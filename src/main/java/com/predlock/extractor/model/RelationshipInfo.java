package com.predlock.extractor.model;

/**
 * Represents a relationship between two JPA entities (e.g., @OneToMany, @ManyToOne).
 * Used inside {@link EntityModel} to describe how entities are connected.
 */
public class RelationshipInfo {

    /** Target entity simple class name, e.g. "OrderItem" */
    private String targetEntity;

    /** Join column name, e.g. "order_id" */
    private String joinColumn;

    /** Fetch type: "LAZY" or "EAGER" */
    private String fetchType;

    /** Relation type: "ONE_TO_MANY", "MANY_TO_ONE", "MANY_TO_MANY", "ONE_TO_ONE" */
    private String relationType;

    public RelationshipInfo() {
    }

    public RelationshipInfo(String targetEntity, String joinColumn, String fetchType, String relationType) {
        this.targetEntity = targetEntity;
        this.joinColumn = joinColumn;
        this.fetchType = fetchType;
        this.relationType = relationType;
    }

    public String getTargetEntity() {
        return targetEntity;
    }

    public void setTargetEntity(String targetEntity) {
        this.targetEntity = targetEntity;
    }

    public String getJoinColumn() {
        return joinColumn;
    }

    public void setJoinColumn(String joinColumn) {
        this.joinColumn = joinColumn;
    }

    public String getFetchType() {
        return fetchType;
    }

    public void setFetchType(String fetchType) {
        this.fetchType = fetchType;
    }

    public String getRelationType() {
        return relationType;
    }

    public void setRelationType(String relationType) {
        this.relationType = relationType;
    }
}
