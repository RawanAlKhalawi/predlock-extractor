package com.predlock.extractor.phase5;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.predlock.extractor.model.ResolvedTransaction;
import com.predlock.extractor.model.SqlOperation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestPhase5 {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void cleanup() {
        new File("deadlock-analyzer-input.json").delete();
    }

    private ResolvedTransaction makeTransaction(String id, String propagation, boolean readOnly,
                                                 List<SqlOperation> ops, List<String> callChain) {
        ResolvedTransaction rt = new ResolvedTransaction();
        rt.setTransactionId(id);
        rt.setPropagation(propagation);
        rt.setReadOnly(readOnly);
        rt.setOrderedStatements(ops);
        rt.setCallChain(callChain);
        return rt;
    }

    @Test
    void testBasicGeneration() throws Exception {
        SqlOperation op = new SqlOperation("SELECT",
                "SELECT * FROM inventory WHERE product_id = ?",
                List.of("inventory"), "product_id = 1",
                "OrderService.placeOrder");

        ResolvedTransaction rt = makeTransaction("OrderService.placeOrder", "REQUIRED", false,
                List.of(op),
                List.of("OrderService.placeOrder", "inventoryRepo.findByProductId"));

        DeadlockAnalyzerInputGenerator gen = new DeadlockAnalyzerInputGenerator();
        String json = gen.generate(List.of(rt), 2, "deadlock-analyzer-input.json");
        JsonNode root = MAPPER.readTree(json);

        JsonNode txs = root.get("transactions");
        assertEquals(2, txs.size());

        // T1
        JsonNode t1 = txs.get(0);
        assertEquals("T1_placeOrder", t1.get("id").asText());
        assertEquals(4000, t1.get("beginTimestamp").asInt());

        JsonNode stmt = t1.get("statements").get(0);
        assertEquals(1, stmt.get("executionOrder").asInt());
        assertEquals("SELECT", stmt.get("type").asText());
        assertEquals("SELECT * FROM inventory WHERE product_id = ?", stmt.get("rawSql").asText());
        assertEquals("inventory", stmt.get("tableNames").get(0).asText());
        assertEquals("product_id = 1", stmt.get("queryConditions").asText());
        assertEquals(1, stmt.get("parameters").get("sym_productId").asInt());
        assertEquals("res1", stmt.get("resultReference").asText());
        assertTrue(stmt.get("triggeringCodeLocation").asText().contains("→"));

        assertEquals("sym_productId > 0", t1.get("pathConditions").get(0).asText());

        // T2
        JsonNode t2 = txs.get(1);
        assertEquals("T2_placeOrder", t2.get("id").asText());
        assertEquals(4001, t2.get("beginTimestamp").asInt());

        // File written
        assertTrue(new File("deadlock-analyzer-input.json").exists());
    }

    @Test
    void testConcurrentCopies3() throws Exception {
        SqlOperation op = new SqlOperation("UPDATE", "UPDATE orders SET status = ?",
                List.of("orders"), null, "OrderService.update");
        ResolvedTransaction rt = makeTransaction("OrderService.update", "REQUIRED", false,
                List.of(op), List.of("OrderService.update"));

        String json = new DeadlockAnalyzerInputGenerator().generate(List.of(rt), 3, "deadlock-analyzer-input.json");
        JsonNode txs = MAPPER.readTree(json).get("transactions");
        assertEquals(3, txs.size());
        assertEquals("T1_update", txs.get(0).get("id").asText());
        assertEquals("T3_update", txs.get(2).get("id").asText());
        assertEquals(4002, txs.get(2).get("beginTimestamp").asInt());
    }

    @Test
    void testGlobalCounterAcrossStatements() throws Exception {
        SqlOperation op1 = new SqlOperation("SELECT", "SELECT * FROM a WHERE id = ?",
                List.of("a"), "id = 1", "S.m");
        SqlOperation op2 = new SqlOperation("UPDATE", "UPDATE b SET x = ?",
                List.of("b"), "id = 2", "S.m");
        ResolvedTransaction rt = makeTransaction("S.m", "REQUIRED", false,
                List.of(op1, op2), List.of("S.m"));

        String json = new DeadlockAnalyzerInputGenerator().generate(List.of(rt), 1, "deadlock-analyzer-input.json");
        JsonNode stmts = MAPPER.readTree(json).get("transactions").get(0).get("statements");
        assertEquals("res1", stmts.get(0).get("resultReference").asText());
        assertEquals("res2", stmts.get(1).get("resultReference").asText());
    }

    @Test
    void testEmptyTransactions() throws Exception {
        String json = new DeadlockAnalyzerInputGenerator().generate(List.of(), 2, "deadlock-analyzer-input.json");
        JsonNode root = MAPPER.readTree(json);
        assertEquals(0, root.get("transactions").size());
    }

    @Test
    void testMultipleWhereColumns() throws Exception {
        SqlOperation op = new SqlOperation("SELECT",
                "SELECT * FROM orders WHERE user_id = ? AND status = ?",
                List.of("orders"), "user_id = 1 AND status = 'NEW'",
                "S.m");
        ResolvedTransaction rt = makeTransaction("S.m", "REQUIRED", false,
                List.of(op), List.of("S.m"));

        String json = new DeadlockAnalyzerInputGenerator().generate(List.of(rt), 1, "deadlock-analyzer-input.json");
        JsonNode tx = MAPPER.readTree(json).get("transactions").get(0);
        JsonNode params = tx.get("statements").get(0).get("parameters");
        assertEquals(1, params.get("sym_userId").asInt());
        assertEquals(1, params.get("sym_status").asInt());

        JsonNode pc = tx.get("pathConditions");
        assertEquals(2, pc.size());
    }
}
