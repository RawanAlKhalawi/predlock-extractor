package com.predlock.extractor;

import com.predlock.extractor.model.EntityModel;
import com.predlock.extractor.model.ResolvedTransaction;
import com.predlock.extractor.model.SqlOperation;
import com.predlock.extractor.model.TransactionTemplate;
import com.predlock.extractor.phase1.EntityModelExtractor;
import com.predlock.extractor.phase2.TransactionBoundaryExtractor;
import com.predlock.extractor.phase3.SqlQueryCollector;
import com.predlock.extractor.phase4.CallGraphPropagator;
import com.predlock.extractor.phase5.DeadlockAnalyzerInputGenerator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Entry point for the predlock-extractor static code analyzer.
 * Wires all five phases together to scan a Spring Boot project and produce
 * {@code deadlock-analyzer-input.json} for the deadlock analyzer.
 */
public class Main {

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: predlock-extractor <project-path> [output-path]");
            System.exit(1);
        }

        String projectPath = args[0];
        String outputPath = args.length >= 2 ? args[1] : "deadlock-analyzer-input.json";

        if (!Files.exists(Path.of(projectPath))) {
            System.err.println("Error: project path does not exist: " + projectPath);
            System.exit(1);
        }

        // Phase 1: Extract entity model
        EntityModel entityModel = null;
        try {
            EntityModelExtractor extractor = new EntityModelExtractor();
            entityModel = extractor.extract(projectPath);
            int entities = entityModel.getEntityToTable().size();
            int relationships = entityModel.getRelationships().size();
            System.out.println("Phase 1 done: " + entities + " entities, " + relationships + " relationships found");
            if (entities == 0) {
                System.out.println("Warning: Phase 1 returned empty results");
            }
        } catch (Exception e) {
            System.err.println("Phase 1 failed: " + e.getMessage());
            return;
        }

        // Phase 2: Extract transaction boundaries
        List<TransactionTemplate> templates = null;
        try {
            TransactionBoundaryExtractor extractor = new TransactionBoundaryExtractor();
            templates = extractor.extract(projectPath);
            System.out.println("Phase 2 done: " + templates.size() + " @Transactional methods found");
            if (templates.isEmpty()) {
                System.out.println("Warning: Phase 2 returned empty results");
            }
        } catch (Exception e) {
            System.err.println("Phase 2 failed: " + e.getMessage());
            return;
        }

        // Phase 3: Collect SQL queries
        Map<String, List<SqlOperation>> sqlMap = null;
        try {
            SqlQueryCollector collector = new SqlQueryCollector();
            sqlMap = collector.collect(projectPath, entityModel);
            int totalOps = sqlMap.values().stream().mapToInt(List::size).sum();
            System.out.println("Phase 3 done: " + totalOps + " SQL operations collected from " + sqlMap.size() + " methods");
            if (sqlMap.isEmpty()) {
                System.out.println("Warning: Phase 3 returned empty results");
            }
        } catch (Exception e) {
            System.err.println("Phase 3 failed: " + e.getMessage());
            return;
        }

        // Phase 4: Propagate through call graph
        List<ResolvedTransaction> resolvedTransactions = null;
        try {
            CallGraphPropagator propagator = new CallGraphPropagator();
            resolvedTransactions = propagator.propagate(templates, sqlMap, entityModel, projectPath);
            System.out.println("Phase 4 done: " + resolvedTransactions.size() + " transactions resolved with full SQL scope");
            if (resolvedTransactions.isEmpty()) {
                System.out.println("Warning: Phase 4 returned empty results");
            }
        } catch (Exception e) {
            System.err.println("Phase 4 failed: " + e.getMessage());
            return;
        }

        // Phase 5: Generate deadlock analyzer input
        try {
            DeadlockAnalyzerInputGenerator generator = new DeadlockAnalyzerInputGenerator();
            generator.generate(resolvedTransactions, 1, outputPath);
            System.out.println("Phase 5 done: deadlock-analyzer-input.json written");
        } catch (Exception e) {
            System.err.println("Phase 5 failed: " + e.getMessage());
        }
    }
}
