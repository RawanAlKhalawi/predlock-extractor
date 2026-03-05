package com.predlock.extractor.phase2;

import com.github.javaparser.ParseProblemException;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.predlock.extractor.model.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Phase 2: Detects transactional method boundaries in Spring Boot source code.
 * <p>
 * Adapted from SpringSentinel's @Transactional detection logic
 * (AnalysisRules.checkBlockingTransactional / checkTransactionTimeout).
 * Uses JavaParser {@link VoidVisitorAdapter} to walk all {@code @Service} and
 * {@code @Component} classes and extract transactional method metadata.
 */
public class TransactionBoundaryExtractor {

    private static final Logger log = LoggerFactory.getLogger(TransactionBoundaryExtractor.class);

    private static final String TRANSACTIONAL_ANNOT = "Transactional";
    private static final String SERVICE_ANNOT = "Service";
    private static final String COMPONENT_ANNOT = "Component";

    private static final Set<String> SPRING_BEAN_ANNOTS = Set.of(
            SERVICE_ANNOT, COMPONENT_ANNOT, "Repository", "Controller", "RestController"
    );

    /**
     * Scans the given project path for transactional methods and extracts
     * their boundaries, including propagation, isolation, readOnly,
     * self-invocation detection, and the list of methods called within each transaction.
     *
     * @param projectPath the root path of the Spring Boot project to analyze
     * @return a list of {@link TransactionTemplate} representing each transactional method
     */
    public List<TransactionTemplate> extract(String projectPath) {
        List<TransactionTemplate> results = new ArrayList<>();

        List<CompilationUnit> cus = parseFromDir(projectPath);

        for (CompilationUnit cu : cus) {
            cu.accept(new TransactionalVisitor(results), null);
        }

        return results;
    }

    /**
     * Recursively parse all .java files under a directory.
     * Same pattern as Phase 1 EntityModelExtractor.
     */
    private List<CompilationUnit> parseFromDir(String dirPath) {
        List<CompilationUnit> results = new ArrayList<>();
        parseFromDir(dirPath, results);
        return results;
    }

    private void parseFromDir(String dirPath, List<CompilationUnit> results) {
        File[] files = new File(dirPath).listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                parseFromDir(file.getAbsolutePath(), results);
            } else if (file.getName().toLowerCase().endsWith(".java")) {
                try {
                    CompilationUnit cu = StaticJavaParser.parse(file);
                    results.add(cu);
                } catch (ParseProblemException e) {
                    log.warn("Parse error in {}: {}", file.getPath(), e.getMessage());
                } catch (Exception e) {
                    log.warn("Error reading {}: {}", file.getPath(), e.getMessage());
                }
            }
        }
    }

    /**
     * Visitor that processes class declarations to find @Transactional methods.
     * Adapted from SpringSentinel's pattern of using
     * {@code m.isAnnotationPresent("Transactional")} and
     * {@code m.getAnnotationByName("Transactional")} to detect and read attributes.
     */
    private static class TransactionalVisitor extends VoidVisitorAdapter<Void> {

        private final List<TransactionTemplate> results;

        TransactionalVisitor(List<TransactionTemplate> results) {
            this.results = results;
        }

        @Override
        public void visit(ClassOrInterfaceDeclaration cls, Void arg) {
            super.visit(cls, arg);

            // Check if class is a Spring bean (@Service, @Component, etc.)
            boolean isSpringBean = cls.getAnnotations().stream()
                    .anyMatch(a -> SPRING_BEAN_ANNOTS.contains(a.getNameAsString()));
            if (!isSpringBean) {
                return;
            }

            String className = cls.getNameAsString();

            // Check for class-level @Transactional (applies to all public methods)
            boolean classLevelTransactional = cls.isAnnotationPresent(TRANSACTIONAL_ANNOT);
            AnnotationExpr classTransactionalAnnot = classLevelTransactional
                    ? cls.getAnnotationByName(TRANSACTIONAL_ANNOT).orElse(null)
                    : null;

            // Collect names of all @Transactional methods in this class for self-invocation detection
            // Adapted from SpringSentinel: filter methods by isAnnotationPresent("Transactional")
            Set<String> transactionalMethodNames = cls.getMethods().stream()
                    .filter(m -> m.isAnnotationPresent(TRANSACTIONAL_ANNOT) || classLevelTransactional)
                    .map(MethodDeclaration::getNameAsString)
                    .collect(Collectors.toSet());

            // Process each method
            for (MethodDeclaration method : cls.getMethods()) {
                boolean methodLevelTransactional = method.isAnnotationPresent(TRANSACTIONAL_ANNOT);

                if (!methodLevelTransactional && !classLevelTransactional) {
                    continue;
                }

                // Use method-level annotation if present, else class-level
                // Adapted from SpringSentinel: m.getAnnotationByName("Transactional")
                AnnotationExpr effectiveAnnot = methodLevelTransactional
                        ? method.getAnnotationByName(TRANSACTIONAL_ANNOT).orElse(null)
                        : classTransactionalAnnot;

                String propagation = "REQUIRED";
                String isolation = "DEFAULT";
                boolean readOnly = false;

                if (effectiveAnnot != null) {
                    propagation = readAnnotationAttr(effectiveAnnot, "propagation", "REQUIRED");
                    isolation = readAnnotationAttr(effectiveAnnot, "isolation", "DEFAULT");
                    String readOnlyVal = readAnnotationAttr(effectiveAnnot, "readOnly", null);
                    if ("true".equals(readOnlyVal)) {
                        readOnly = true;
                    }
                }

                // Strip enum prefix (e.g. "Propagation.REQUIRED" → "REQUIRED")
                if (propagation.contains(".")) {
                    propagation = propagation.substring(propagation.lastIndexOf('.') + 1);
                }
                if (isolation.contains(".")) {
                    isolation = isolation.substring(isolation.lastIndexOf('.') + 1);
                }

                // Collect all method calls inside this method body
                // Adapted from SpringSentinel: m.findAll(MethodCallExpr.class)
                List<String> calledMethods = new ArrayList<>();
                boolean selfInvocation = false;

                for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                    String callName;
                    if (call.getScope().isPresent()) {
                        callName = call.getScope().get().toString() + "." + call.getNameAsString();
                    } else {
                        callName = call.getNameAsString();
                    }
                    calledMethods.add(callName);

                    // Self-invocation: calling another @Transactional method in the same class
                    // without scope (direct call) or via "this"
                    String calledName = call.getNameAsString();
                    if (transactionalMethodNames.contains(calledName)
                            && !calledName.equals(method.getNameAsString())) {
                        boolean isSameClass = call.getScope().isEmpty()
                                || call.getScope().get().toString().equals("this");
                        if (isSameClass) {
                            selfInvocation = true;
                        }
                    }
                }

                TransactionTemplate template = new TransactionTemplate();
                template.setMethodSignature(className + "." + method.getNameAsString());
                template.setClassName(className);
                template.setMethodName(method.getNameAsString());
                template.setPropagation(propagation);
                template.setIsolation(isolation);
                template.setReadOnly(readOnly);
                template.setSelfInvocation(selfInvocation);
                template.setCalledMethods(calledMethods);

                results.add(template);
            }
        }
    }

    /**
     * Read a named attribute from an annotation expression.
     * Returns the default value if the attribute is not found.
     */
    private static String readAnnotationAttr(AnnotationExpr annotation, String attrName, String defaultValue) {
        if (annotation instanceof NormalAnnotationExpr normal) {
            for (MemberValuePair mvp : normal.getPairs()) {
                if (mvp.getNameAsString().equals(attrName)) {
                    String val = mvp.getValue().toString();
                    // Strip quotes
                    if (val.startsWith("\"") && val.endsWith("\"")) {
                        val = val.substring(1, val.length() - 1);
                    }
                    return val;
                }
            }
        }
        return defaultValue;
    }
}
