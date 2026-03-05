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
    public String generate(List<ResolvedTransaction> transactions, int concurrentCopies) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);

        List<Map<String, Object>> txList = new ArrayList<>();
        int globalCounter = 1;

        for (ResolvedTransaction rt : transactions) {
            // Extract method name from transactionId (e.g. "OrderService.placeOrder" → "placeOrder")
            String methodName = rt.getTransactionId();
            int dotIdx = methodName.lastIndexOf('.');
            if (dotIdx >= 0) {
                methodName = methodName.substring(dotIdx + 1);
            }

            for (int copy = 1; copy <= concurrentCopies; copy++) {
                Map<String, Object> tx = new LinkedHashMap<>();
                tx.put("id", "T" + copy + "_" + methodName);
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

                        // Triggering code location: join callChain with " → "
                        String trigLoc = "";
                        if (rt.getCallChain() != null && !rt.getCallChain().isEmpty()) {
                            trigLoc = String.join(" → ", rt.getCallChain());
                        }
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
        mapper.writeValue(new File("deadlock-analyzer-input.json"), root);
        log.info("Written deadlock-analyzer-input.json");

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
}
