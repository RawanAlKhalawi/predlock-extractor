package com.predlock.extractor.phase5;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.predlock.extractor.model.ResolvedTransaction;
import com.predlock.extractor.model.SqlOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 5: Generates the final JSON output consumed by the deadlock analyzer.
 * Uses Jackson to serialize the resolved transactions into {@code deadlock-analyzer-input.json}
 * written to the current working directory.
 */
public class DeadlockAnalyzerInputGenerator {

    private static final Logger log = LoggerFactory.getLogger(DeadlockAnalyzerInputGenerator.class);
    private static final Pattern WHERE_COL_PATTERN = Pattern.compile("(\\w+)\\s*[=<>!]");

    /**
     * Generates the deadlock analyzer input JSON from resolved transactions.
     *
     * @param transactions     the fully resolved transactions from Phase 4
     * @param concurrentCopies number of concurrent copies per transaction (default 2)
     * @return the JSON string
     * @throws IOException if writing the JSON file fails
     */
    public String generate(List<ResolvedTransaction> transactions, int concurrentCopies, String outputPath) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);

        // --------------------------------------------------------------------
        // Pre-pass: detect simple-method-name collisions.
        //
        // The transactionId from Phase 4 is "<SimpleClassName>.<methodName>".
        // The original implementation collapsed it to just "<methodName>" and
        // emitted ids of the form "T<n>_<methodName>". When two services
        // declare a method with the same name (the canonical example is
        // CustomerServiceImpl.changePassword vs
        // AdminSecurityServiceImpl.changePassword), both contributed entries
        // to the JSON sharing the same id, and the downstream analyzer
        // treated their statements as belonging to one transaction —
        // producing phantom cycles that mixed unrelated SQL.
        //
        // We disambiguate ONLY the colliding ids (qualifying them with the
        // class name) so the common case is byte-for-byte unchanged for
        // backward compatibility with existing analyzer tests and reports.
        // --------------------------------------------------------------------
        Map<String, Integer> simpleNameCounts = new HashMap<>();
        for (ResolvedTransaction rt : transactions) {
            simpleNameCounts.merge(simpleMethodName(rt.getTransactionId()), 1, Integer::sum);
        }

        List<Map<String, Object>> txList = new ArrayList<>();
        int globalCounter = 1;

        for (ResolvedTransaction rt : transactions) {
            // Compute the id segment used after the "T<n>_" prefix.
            // - Non-colliding methods: bare method name (e.g. "saveOrder") —
            //   matches the original output for backward compatibility.
            // - Colliding methods: "<ClassName>_<methodName>" so e.g.
            //     T1_CustomerServiceImpl_changePassword
            //     T1_AdminSecurityServiceImpl_changePassword
            //   remain distinguishable in the analyzer's input and reports.
            String txId = rt.getTransactionId();
            String simpleName = simpleMethodName(txId);
            String idSuffix = simpleNameCounts.getOrDefault(simpleName, 0) > 1
                ? toIdSegment(txId)
                : simpleName;

            for (int copy = 1; copy <= concurrentCopies; copy++) {
                Map<String, Object> tx = new LinkedHashMap<>();
                tx.put("id", "T" + copy + "_" + idSuffix);
                tx.put("beginTimestamp", 4000 + copy - 1);

                List<Map<String, Object>> stmts = new ArrayList<>();
                Set<String> allWhereColumns = new LinkedHashSet<>();
                int execOrder = 1;

                List<SqlOperation> ops = rt.getOrderedStatements();
                if (ops != null) {
                    for (SqlOperation op : ops) {
                        Map<String, Object> stmt = new LinkedHashMap<>();
                        stmt.put("executionOrder", execOrder++);
                        stmt.put("type", op.getType());
                        stmt.put("rawSql", op.getRawSql());
                        stmt.put("tableNames", op.getTables() != null ? op.getTables() : List.of());
                        stmt.put("queryConditions", op.getWhereCondition() != null ? op.getWhereCondition() : "");

                        // Parameters: one entry per WHERE column as sym_<columnName>: 1
                        Map<String, Integer> params = new LinkedHashMap<>();
                        List<String> whereCols = extractWhereColumns(op.getWhereCondition());
                        for (String col : whereCols) {
                            String symName = "sym_" + snakeToCamel(col);
                            params.put(symName, 1);
                            allWhereColumns.add(symName);
                        }
                        stmt.put("parameters", params);
                        stmt.put("resultReference", "res" + globalCounter++);
                        stmt.put("lineNumber", op.getLineNumber()); // add after resultReference line

                        // Triggering code location: join callChain with " → "
//                        String trigLoc = "";
//                        if (rt.getCallChain() != null && !rt.getCallChain().isEmpty()) {
//                            trigLoc = String.join(" → ", rt.getCallChain());
//                        }
//                        stmt.put("triggeringCodeLocation", trigLoc);

                        String trigLoc = buildTriggeringLocation(
                            rt.getCallChain(), op.getSourceMethod(), rt.getTransactionId(),
                            op.getLineNumber());
                        stmt.put("triggeringCodeLocation", trigLoc);

                        stmts.add(stmt);
                    }
                }

                tx.put("statements", stmts);

                // Path conditions: sym_<col> > 0 for each WHERE column
                List<String> pathConditions = new ArrayList<>();
                for (String sym : allWhereColumns) {
                    pathConditions.add(sym + " > 0");
                }
                tx.put("pathConditions", pathConditions);

                txList.add(tx);
            }
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("transactions", txList);

        String json = mapper.writeValueAsString(root);

        // Write to file
        File outputFile = new File(outputPath);
        if (outputFile.getParentFile() != null) {
            outputFile.getParentFile().mkdirs();
        }
        mapper.writeValue(outputFile, root);
        log.info("Written {}", outputPath);

        return json;
    }

    /**
     * Extract column names from a WHERE condition string.
     * E.g. "product_id = 1" → ["product_id"]
     */
    private List<String> extractWhereColumns(String whereCondition) {
        List<String> cols = new ArrayList<>();
        if (whereCondition == null || whereCondition.isBlank()) {
            return cols;
        }
        Matcher m = WHERE_COL_PATTERN.matcher(whereCondition);
        while (m.find()) {
            cols.add(m.group(1));
        }
        return cols;
    }

    /**
     * Convert snake_case column name to camelCase for sym_ parameter names.
     * E.g. "product_id" → "productId"
     */
    private String snakeToCamel(String snake) {
        if (!snake.contains("_")) {
            return snake;
        }
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else {
                sb.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return sb.toString();
    }

    /**
     * Returns the trailing method name from a Phase 4 transaction id of the
     * form {@code "<SimpleClassName>.<methodName>"}. Returns the input
     * unchanged if it has no dot, and the empty string for null input.
     */
    private String simpleMethodName(String transactionId) {
        if (transactionId == null) return "";
        int dot = transactionId.lastIndexOf('.');
        return dot >= 0 ? transactionId.substring(dot + 1) : transactionId;
    }

    /**
     * Converts a transaction id like {@code "CustomerServiceImpl.changePassword"}
     * into a JSON-safe id segment by replacing dots with underscores. Used only
     * for transactions whose simple method name collides with another
     * transaction's simple method name; see the pre-pass in {@link #generate}.
     */
    private String toIdSegment(String transactionId) {
        if (transactionId == null) return "";
        return transactionId.replace('.', '_');
    }

    private String buildTriggeringLocation(List<String> chain, String sourceMethod,
                                           String transactionId, int lineNumber) {
        // Embed line in the string — serves as fallback when the analyzer
        // creates new SQLStatement objects that lose the lineNumber field.
        String lineSuffix = lineNumber > 0 ? " (line " + lineNumber + ")" : "";

        if (chain == null || chain.isEmpty()) {
            return sourceMethod != null ? sourceMethod + lineSuffix : "";
        }
        if (sourceMethod == null || sourceMethod.isBlank()) {
            return String.join(" → ", chain);
        }
        int rootIdx = 0;
        for (int i = 0; i < chain.size(); i++) {
            if (chain.get(i).equals(transactionId)) { rootIdx = i; break; }
        }
        int srcIdx = -1;
        for (int i = rootIdx; i < chain.size(); i++) {
            if (chain.get(i).equals(sourceMethod)) { srcIdx = i; break; }
        }
        if (srcIdx >= 0) {
            return String.join(" → ", chain.subList(rootIdx, srcIdx + 1)) + lineSuffix;
        }
        return transactionId + " → " + sourceMethod + lineSuffix;
    }
}