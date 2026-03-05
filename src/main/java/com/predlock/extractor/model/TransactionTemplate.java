package com.predlock.extractor.model;

import java.util.List;

/**
 * Output of Phase 2. Represents a transactional method boundary detected in Spring Boot
 * source code, including propagation, isolation, readOnly attributes, self-invocation
 * detection, and the list of called methods within the transaction scope.
 */
public class TransactionTemplate {

    private String methodSignature;
    private String className;
    private String methodName;
    private String propagation;
    private String isolation;
    private boolean readOnly;
    private boolean selfInvocation;
    private List<String> calledMethods;

    public TransactionTemplate() {
    }

    public String getMethodSignature() {
        return methodSignature;
    }

    public void setMethodSignature(String methodSignature) {
        this.methodSignature = methodSignature;
    }

    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public String getMethodName() {
        return methodName;
    }

    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }

    public String getPropagation() {
        return propagation;
    }

    public void setPropagation(String propagation) {
        this.propagation = propagation;
    }

    public String getIsolation() {
        return isolation;
    }

    public void setIsolation(String isolation) {
        this.isolation = isolation;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }

    public boolean isSelfInvocation() {
        return selfInvocation;
    }

    public void setSelfInvocation(boolean selfInvocation) {
        this.selfInvocation = selfInvocation;
    }

    public List<String> getCalledMethods() {
        return calledMethods;
    }

    public void setCalledMethods(List<String> calledMethods) {
        this.calledMethods = calledMethods;
    }
}
