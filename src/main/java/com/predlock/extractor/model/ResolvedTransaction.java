package com.predlock.extractor.model;

import java.util.List;

/**
 * Output of Phase 4. Represents a fully resolved transaction with its associated SQL
 * operations propagated through the call graph. Ready for deadlock analysis input generation.
 */
public class ResolvedTransaction {

    private String transactionId;
    private String propagation;
    private boolean readOnly;
    private List<SqlOperation> orderedStatements;
    private List<String> callChain;

    public ResolvedTransaction() {
    }

    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }

    public String getPropagation() {
        return propagation;
    }

    public void setPropagation(String propagation) {
        this.propagation = propagation;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }

    public List<SqlOperation> getOrderedStatements() {
        return orderedStatements;
    }

    public void setOrderedStatements(List<SqlOperation> orderedStatements) {
        this.orderedStatements = orderedStatements;
    }

    public List<String> getCallChain() {
        return callChain;
    }

    public void setCallChain(List<String> callChain) {
        this.callChain = callChain;
    }
}
