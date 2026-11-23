//package com.predlock.extractor.phase4;
//
//import com.predlock.extractor.model.*;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.io.TempDir;
//
//import java.io.IOException;
//import java.nio.file.Files;
//import java.nio.file.Path;
//import java.util.*;
//
//import static org.junit.jupiter.api.Assertions.*;
//
//class TestPhase4 {
//
//    /**
//     * Test basic propagation: a single @Transactional method with SQL ops from sqlMap.
//     */
//    @Test
//    void testBasicPropagation() {
//        TransactionTemplate template = new TransactionTemplate();
//        template.setMethodSignature("OrderService.placeOrder");
//        template.setClassName("OrderService");
//        template.setMethodName("placeOrder");
//        template.setPropagation("REQUIRED");
//        template.setIsolation("DEFAULT");
//        template.setReadOnly(false);
//        template.setSelfInvocation(false);
//        template.setCalledMethods(List.of("orderRepo.save"));
//
//        SqlOperation op = new SqlOperation("INSERT",
//                "INSERT INTO orders (user_id) VALUES (?)",
//                List.of("orders"), null, "orderRepo.save");
//
//        Map<String, List<SqlOperation>> sqlMap = new HashMap<>();
//        sqlMap.put("orderRepo.save", List.of(op));
//
//        EntityModel entityModel = new EntityModel();
//
//        CallGraphPropagator propagator = new CallGraphPropagator();
//        List<ResolvedTransaction> results = propagator.propagate(
//                List.of(template), sqlMap, entityModel);
//
//        assertEquals(1, results.size());
//        ResolvedTransaction resolved = results.get(0);
//        assertEquals("OrderService.placeOrder", resolved.getTransactionId());
//        assertEquals("REQUIRED", resolved.getPropagation());
//        assertFalse(resolved.isReadOnly());
//        assertEquals(1, resolved.getOrderedStatements().size());
//        assertEquals("INSERT", resolved.getOrderedStatements().get(0).getType());
//        assertTrue(resolved.getCallChain().contains("OrderService.placeOrder"));
//    }
//
//    /**
//     * Test REQUIRES_NEW propagation: called method with REQUIRES_NEW should be skipped.
//     */
//    @Test
//    void testRequiresNewSkipped() {
//        TransactionTemplate outer = new TransactionTemplate();
//        outer.setMethodSignature("OrderService.placeOrder");
//        outer.setClassName("OrderService");
//        outer.setMethodName("placeOrder");
//        outer.setPropagation("REQUIRED");
//        outer.setReadOnly(false);
//        outer.setSelfInvocation(false);
//        outer.setCalledMethods(List.of("AuditService.logAction"));
//
//        TransactionTemplate inner = new TransactionTemplate();
//        inner.setMethodSignature("AuditService.logAction");
//        inner.setClassName("AuditService");
//        inner.setMethodName("logAction");
//        inner.setPropagation("REQUIRES_NEW");
//        inner.setReadOnly(false);
//        inner.setSelfInvocation(false);
//        inner.setCalledMethods(List.of());
//
//        SqlOperation auditOp = new SqlOperation("INSERT",
//                "INSERT INTO audit_log (action) VALUES (?)",
//                List.of("audit_log"), null, "AuditService.logAction");
//
//        Map<String, List<SqlOperation>> sqlMap = new HashMap<>();
//        sqlMap.put("AuditService.logAction", List.of(auditOp));
//
//        EntityModel entityModel = new EntityModel();
//
//        CallGraphPropagator propagator = new CallGraphPropagator();
//        List<ResolvedTransaction> results = propagator.propagate(
//                List.of(outer, inner), sqlMap, entityModel);
//
//        // The outer transaction should NOT contain the audit INSERT
//        ResolvedTransaction outerResolved = results.get(0);
//        assertEquals(0, outerResolved.getOrderedStatements().size());
//    }
//
//    /**
//     * Test implicit SELECT from lazy relationship getter.
//     */
//    @Test
//    void testImplicitSelectLazyGetter() {
//        TransactionTemplate template = new TransactionTemplate();
//        template.setMethodSignature("OrderService.processOrder");
//        template.setClassName("OrderService");
//        template.setMethodName("processOrder");
//        template.setPropagation("REQUIRED");
//        template.setReadOnly(false);
//        template.setSelfInvocation(false);
//        template.setCalledMethods(List.of("order.getItems"));
//
//        Map<String, List<SqlOperation>> sqlMap = new HashMap<>();
//
//        EntityModel entityModel = new EntityModel();
//        entityModel.getEntityToTable().put("Order", "orders");
//        entityModel.getEntityToTable().put("OrderItem", "order_items");
//        entityModel.getRelationships().put("Order.items",
//                new RelationshipInfo("OrderItem", "order_id", "LAZY", "ONE_TO_MANY"));
//
//        CallGraphPropagator propagator = new CallGraphPropagator();
//        List<ResolvedTransaction> results = propagator.propagate(
//                List.of(template), sqlMap, entityModel);
//
//        ResolvedTransaction resolved = results.get(0);
//        assertEquals(1, resolved.getOrderedStatements().size());
//        SqlOperation implicitOp = resolved.getOrderedStatements().get(0);
//        assertEquals("SELECT", implicitOp.getType());
//        assertEquals("SELECT * FROM order_items WHERE order_id = ?", implicitOp.getRawSql());
//        assertTrue(implicitOp.getTables().contains("order_items"));
//    }
//
//    /**
//     * Test implicit UPDATE from setter in non-readOnly transaction.
//     */
//    @Test
//    void testImplicitUpdateSetter() {
//        TransactionTemplate template = new TransactionTemplate();
//        template.setMethodSignature("OrderService.updateOrder");
//        template.setClassName("OrderService");
//        template.setMethodName("updateOrder");
//        template.setPropagation("REQUIRED");
//        template.setReadOnly(false);
//        template.setSelfInvocation(false);
//        template.setCalledMethods(List.of("order.setUserId"));
//
//        Map<String, List<SqlOperation>> sqlMap = new HashMap<>();
//
//        EntityModel entityModel = new EntityModel();
//        entityModel.getEntityToTable().put("Order", "orders");
//        entityModel.getFieldToColumn().put("Order.userId", "user_id");
//
//        CallGraphPropagator propagator = new CallGraphPropagator();
//        List<ResolvedTransaction> results = propagator.propagate(
//                List.of(template), sqlMap, entityModel);
//
//        ResolvedTransaction resolved = results.get(0);
//        assertEquals(1, resolved.getOrderedStatements().size());
//        SqlOperation implicitOp = resolved.getOrderedStatements().get(0);
//        assertEquals("UPDATE", implicitOp.getType());
//        assertEquals("UPDATE orders SET user_id = ? WHERE id = ?", implicitOp.getRawSql());
//    }
//
//    /**
//     * Test that setter in readOnly transaction does NOT produce implicit UPDATE.
//     */
//    @Test
//    void testNoImplicitUpdateInReadOnly() {
//        TransactionTemplate template = new TransactionTemplate();
//        template.setMethodSignature("OrderService.viewOrder");
//        template.setClassName("OrderService");
//        template.setMethodName("viewOrder");
//        template.setPropagation("REQUIRED");
//        template.setReadOnly(true);
//        template.setSelfInvocation(false);
//        template.setCalledMethods(List.of("order.setUserId"));
//
//        Map<String, List<SqlOperation>> sqlMap = new HashMap<>();
//
//        EntityModel entityModel = new EntityModel();
//        entityModel.getEntityToTable().put("Order", "orders");
//        entityModel.getFieldToColumn().put("Order.userId", "user_id");
//
//        CallGraphPropagator propagator = new CallGraphPropagator();
//        List<ResolvedTransaction> results = propagator.propagate(
//                List.of(template), sqlMap, entityModel);
//
//        ResolvedTransaction resolved = results.get(0);
//        assertEquals(0, resolved.getOrderedStatements().size());
//    }
//
//    /**
//     * Test call graph built from actual @Service source files.
//     */
//    @Test
//    void testCallGraphFromSource(@TempDir Path tempDir) throws IOException {
//        String serviceCode = """
//                import org.springframework.stereotype.Service;
//                import org.springframework.transaction.annotation.Transactional;
//
//                @Service
//                public class OrderService {
//                    @Transactional
//                    public void placeOrder() {
//                        inventoryRepo.findByProductId(1L);
//                        orderRepo.save(null);
//                    }
//                }
//                """;
//        Files.writeString(tempDir.resolve("OrderService.java"), serviceCode);
//
//        TransactionTemplate template = new TransactionTemplate();
//        template.setMethodSignature("OrderService.placeOrder");
//        template.setClassName("OrderService");
//        template.setMethodName("placeOrder");
//        template.setPropagation("REQUIRED");
//        template.setReadOnly(false);
//        template.setSelfInvocation(false);
//        template.setCalledMethods(List.of());
//
//        SqlOperation selectOp = new SqlOperation("SELECT",
//                "SELECT * FROM inventory WHERE product_id = ?",
//                List.of("inventory"), "product_id = ?", "inventoryRepo.findByProductId");
//        SqlOperation insertOp = new SqlOperation("INSERT",
//                "INSERT INTO orders VALUES (?)",
//                List.of("orders"), null, "orderRepo.save");
//
//        Map<String, List<SqlOperation>> sqlMap = new HashMap<>();
//        sqlMap.put("inventoryRepo.findByProductId", List.of(selectOp));
//        sqlMap.put("orderRepo.save", List.of(insertOp));
//
//        EntityModel entityModel = new EntityModel();
//
//        CallGraphPropagator propagator = new CallGraphPropagator();
//        List<ResolvedTransaction> results = propagator.propagate(
//                List.of(template), sqlMap, entityModel, tempDir.toString());
//
//        ResolvedTransaction resolved = results.get(0);
//        assertEquals(2, resolved.getOrderedStatements().size());
//        assertEquals("SELECT", resolved.getOrderedStatements().get(0).getType());
//        assertEquals("INSERT", resolved.getOrderedStatements().get(1).getType());
//        assertTrue(resolved.getCallChain().contains("OrderService.placeOrder"));
//    }
//
//    /**
//     * Test findById → save pattern generates SELECT before UPDATE.
//     */
//    @Test
//    void testFindByIdThenSaveGeneratesSelectBeforeUpdate(@TempDir Path tempDir) throws IOException {
//        String serviceCode = """
//                import org.springframework.stereotype.Service;
//                import org.springframework.transaction.annotation.Transactional;
//
//                @Service
//                public class ProductService {
//                    private ProductRepository productRepository;
//
//                    @Transactional
//                    public void updateProduct(Long productId, String newName) {
//                        Product product = productRepository.findById(productId).orElseThrow();
//                        product.setName(newName);
//                        productRepository.save(product);
//                    }
//                }
//                """;
//        Files.writeString(tempDir.resolve("ProductService.java"), serviceCode);
//
//        TransactionTemplate template = new TransactionTemplate();
//        template.setMethodSignature("ProductService.updateProduct");
//        template.setClassName("ProductService");
//        template.setMethodName("updateProduct");
//        template.setPropagation("REQUIRED");
//        template.setReadOnly(false);
//        template.setSelfInvocation(false);
//        template.setCalledMethods(List.of());
//
//        SqlOperation selectOp = new SqlOperation("SELECT",
//                "SELECT * FROM product WHERE id = ?",
//                List.of("product"), "id = ?", "ProductRepository.findById");
//        SqlOperation updateOp = new SqlOperation("UPDATE",
//                "UPDATE product SET name = ? WHERE id = ?",
//                List.of("product"), "id = ?", "productRepository.save");
//
//        Map<String, List<SqlOperation>> sqlMap = new HashMap<>();
//        sqlMap.put("ProductRepository.findById", List.of(selectOp));
//        sqlMap.put("productRepository.save", List.of(updateOp));
//
//        EntityModel entityModel = new EntityModel();
//        entityModel.getEntityToTable().put("Product", "product");
//
//        CallGraphPropagator propagator = new CallGraphPropagator();
//        List<ResolvedTransaction> results = propagator.propagate(
//                List.of(template), sqlMap, entityModel, tempDir.toString());
//
//        ResolvedTransaction resolved = results.get(0);
//        // Should have SELECT (from findById) then UPDATE (from save)
//        assertTrue(resolved.getOrderedStatements().size() >= 2,
//                "Expected at least 2 statements, got: " + resolved.getOrderedStatements().size());
//        assertEquals("SELECT", resolved.getOrderedStatements().get(0).getType(),
//                "First statement should be SELECT from findById");
//        assertEquals("UPDATE", resolved.getOrderedStatements().get(1).getType(),
//                "Second statement should be UPDATE from save");
//    }
//
//    /**
//     * BUG 1: SELECT from findById must come BEFORE implicit UPDATEs from setters.
//     */
//    @Test
//    void testSelectBeforeImplicitUpdates(@TempDir Path tempDir) throws IOException {
//        String serviceCode = """
//                import org.springframework.stereotype.Service;
//                import org.springframework.transaction.annotation.Transactional;
//
//                @Service
//                public class ProductService {
//                    private ProductRepository productRepository;
//
//                    @Transactional
//                    public void updateProduct(Long productId, String newName, double newPrice) {
//                        Product product = productRepository.findById(productId).orElseThrow();
//                        product.setName(newName);
//                        product.setPrice(newPrice);
//                        productRepository.save(product);
//                    }
//                }
//                """;
//        Files.writeString(tempDir.resolve("ProductService.java"), serviceCode);
//
//        TransactionTemplate template = new TransactionTemplate();
//        template.setMethodSignature("ProductService.updateProduct");
//        template.setClassName("ProductService");
//        template.setMethodName("updateProduct");
//        template.setPropagation("REQUIRED");
//        template.setReadOnly(false);
//        template.setSelfInvocation(false);
//        template.setCalledMethods(List.of());
//
//        SqlOperation selectOp = new SqlOperation("SELECT",
//                "SELECT * FROM product WHERE id = ?",
//                List.of("product"), "id = ?", "ProductRepository.findById");
//        SqlOperation updateOp = new SqlOperation("UPDATE",
//                "UPDATE product SET name = ? WHERE id = ?",
//                List.of("product"), "id = ?", "productRepository.save");
//
//        Map<String, List<SqlOperation>> sqlMap = new HashMap<>();
//        sqlMap.put("ProductRepository.findById", List.of(selectOp));
//        sqlMap.put("productRepository.save", List.of(updateOp));
//
//        EntityModel entityModel = new EntityModel();
//        entityModel.getEntityToTable().put("Product", "product");
//        entityModel.getFieldToColumn().put("Product.name", "name");
//        entityModel.getFieldToColumn().put("Product.price", "price");
//
//        CallGraphPropagator propagator = new CallGraphPropagator();
//        List<ResolvedTransaction> results = propagator.propagate(
//                List.of(template), sqlMap, entityModel, tempDir.toString());
//
//        ResolvedTransaction resolved = results.get(0);
//        // SELECT must be first
//        assertEquals("SELECT", resolved.getOrderedStatements().get(0).getType(),
//                "First statement should be SELECT, got: " + resolved.getOrderedStatements());
//    }
//
//    /**
//     * BUG 2: saveAll() with list built from new objects should be INSERT.
//     */
//    @Test
//    void testSaveAllNewObjectsIsInsert(@TempDir Path tempDir) throws IOException {
//        String serviceCode = """
//                import org.springframework.stereotype.Service;
//                import org.springframework.transaction.annotation.Transactional;
//                import java.util.ArrayList;
//                import java.util.List;
//
//                @Service
//                public class ProductService {
//                    private ProductRepository productRepository;
//
//                    @Transactional
//                    public void createProducts() {
//                        List<Product> newProducts = new ArrayList<>();
//                        productRepository.saveAll(newProducts);
//                    }
//                }
//                """;
//        Files.writeString(tempDir.resolve("ProductService.java"), serviceCode);
//
//        TransactionTemplate template = new TransactionTemplate();
//        template.setMethodSignature("ProductService.createProducts");
//        template.setClassName("ProductService");
//        template.setMethodName("createProducts");
//        template.setPropagation("REQUIRED");
//        template.setReadOnly(false);
//        template.setSelfInvocation(false);
//        template.setCalledMethods(List.of());
//
//        SqlOperation updateOp = new SqlOperation("UPDATE",
//                "UPDATE product SET name = ? WHERE id = ?",
//                List.of("product"), "id = ?", "productRepository.saveAll");
//
//        Map<String, List<SqlOperation>> sqlMap = new HashMap<>();
//        sqlMap.put("productRepository.saveAll", List.of(updateOp));
//
//        EntityModel entityModel = new EntityModel();
//        entityModel.getEntityToTable().put("Product", "product");
//
//        CallGraphPropagator propagator = new CallGraphPropagator();
//        List<ResolvedTransaction> results = propagator.propagate(
//                List.of(template), sqlMap, entityModel, tempDir.toString());
//
//        ResolvedTransaction resolved = results.get(0);
//        assertEquals(1, resolved.getOrderedStatements().size());
//        assertEquals("INSERT", resolved.getOrderedStatements().get(0).getType(),
//                "saveAll with new list should be INSERT");
//    }
//
//    /**
//     * BUG 3: setters on new objects should NOT generate implicit UPDATE SQL.
//     */
//    @Test
//    void testSetterOnNewObjectNoImplicitUpdate(@TempDir Path tempDir) throws IOException {
//        String serviceCode = """
//                import org.springframework.stereotype.Service;
//                import org.springframework.transaction.annotation.Transactional;
//
//                @Service
//                public class TransactionService {
//                    private UserTransactionRepository userTransactionRepository;
//
//                    @Transactional
//                    public void createTransaction(Long userId) {
//                        UserTransaction transaction = new UserTransaction();
//                        transaction.setUserId(userId);
//                        transaction.setStatus("PENDING");
//                        userTransactionRepository.save(transaction);
//                    }
//                }
//                """;
//        Files.writeString(tempDir.resolve("TransactionService.java"), serviceCode);
//
//        TransactionTemplate template = new TransactionTemplate();
//        template.setMethodSignature("TransactionService.createTransaction");
//        template.setClassName("TransactionService");
//        template.setMethodName("createTransaction");
//        template.setPropagation("REQUIRED");
//        template.setReadOnly(false);
//        template.setSelfInvocation(false);
//        template.setCalledMethods(List.of());
//
//        SqlOperation insertOp = new SqlOperation("INSERT",
//                "INSERT INTO user_transaction VALUES (?)",
//                List.of("user_transaction"), null, "userTransactionRepository.save");
//
//        Map<String, List<SqlOperation>> sqlMap = new HashMap<>();
//        sqlMap.put("userTransactionRepository.save", List.of(insertOp));
//
//        EntityModel entityModel = new EntityModel();
//        entityModel.getEntityToTable().put("UserTransaction", "user_transaction");
//        entityModel.getFieldToColumn().put("UserTransaction.userId", "user_id");
//        entityModel.getFieldToColumn().put("UserTransaction.status", "status");
//
//        CallGraphPropagator propagator = new CallGraphPropagator();
//        List<ResolvedTransaction> results = propagator.propagate(
//                List.of(template), sqlMap, entityModel, tempDir.toString());
//
//        ResolvedTransaction resolved = results.get(0);
//        // Should only have the INSERT from save(), no implicit UPDATEs from setters
//        assertEquals(1, resolved.getOrderedStatements().size(),
//                "Should have only 1 statement (INSERT), got: " + resolved.getOrderedStatements().size());
//        assertEquals("INSERT", resolved.getOrderedStatements().get(0).getType());
//    }
//
//    /**
//     * BUG 1 (current issue): saveAll() with new objects and NO sqlMap entry should generate INSERT.
//     */
//    @Test
//    void testSaveAllNewObjectsGeneratesInsertWhenNoSqlMapEntry(@TempDir Path tempDir) throws IOException {
//        String serviceCode = """
//                import org.springframework.stereotype.Service;
//                import org.springframework.transaction.annotation.Transactional;
//                import java.util.ArrayList;
//                import java.util.List;
//
//                @Service
//                public class ProductService {
//                    private ProductRepository productRepository;
//
//                    @Transactional
//                    public void deleteAndInsertProducts(String name) {
//                        productRepository.deleteByName(name);
//                        List<Product> newProducts = new ArrayList<>();
//                        for (int i = 0; i < 3; i++) {
//                            Product product = new Product();
//                            product.setName(name);
//                            newProducts.add(product);
//                        }
//                        productRepository.saveAll(newProducts);
//                    }
//                }
//                """;
//        Files.writeString(tempDir.resolve("ProductService.java"), serviceCode);
//
//        TransactionTemplate template = new TransactionTemplate();
//        template.setMethodSignature("ProductService.deleteAndInsertProducts");
//        template.setClassName("ProductService");
//        template.setMethodName("deleteAndInsertProducts");
//        template.setPropagation("REQUIRED");
//        template.setReadOnly(false);
//        template.setSelfInvocation(false);
//        template.setCalledMethods(List.of());
//
//        SqlOperation deleteOp = new SqlOperation("DELETE",
//                "DELETE FROM product WHERE name = ?",
//                List.of("product"), "name = ?", "ProductRepository.deleteByName");
//
//        Map<String, List<SqlOperation>> sqlMap = new HashMap<>();
//        sqlMap.put("ProductRepository.deleteByName", List.of(deleteOp));
//        // Note: NO sqlMap entry for productRepository.saveAll — it must be generated
//
//        EntityModel entityModel = new EntityModel();
//        entityModel.getEntityToTable().put("Product", "product");
//
//        CallGraphPropagator propagator = new CallGraphPropagator();
//        List<ResolvedTransaction> results = propagator.propagate(
//                List.of(template), sqlMap, entityModel, tempDir.toString());
//
//        ResolvedTransaction resolved = results.get(0);
//        // Should have DELETE then INSERT
//        assertTrue(resolved.getOrderedStatements().size() >= 2,
//                "Expected at least 2 statements, got: " + resolved.getOrderedStatements().size()
//                + " → " + resolved.getOrderedStatements().stream().map(o -> o.getType() + ":" + o.getRawSql()).toList());
//        assertEquals("DELETE", resolved.getOrderedStatements().get(0).getType());
//        assertEquals("INSERT", resolved.getOrderedStatements().get(1).getType(),
//                "saveAll with new list should generate INSERT, got: "
//                + resolved.getOrderedStatements().stream().map(o -> o.getType() + ":" + o.getRawSql()).toList());
//    }
//
//    /**
//     * BUG 2 (current issue): Two save() calls on same new object — first=INSERT, second=UPDATE.
//     */
//    @Test
//    void testTwoSavesOnSameNewObject(@TempDir Path tempDir) throws IOException {
//        String serviceCode = """
//                import org.springframework.stereotype.Service;
//                import org.springframework.transaction.annotation.Transactional;
//
//                @Service
//                public class TransactionService {
//                    private UserTransactionRepository userTransactionRepository;
//
//                    @Transactional
//                    public void processTransaction(Long userId) {
//                        UserTransaction transaction = new UserTransaction();
//                        transaction.setUserId(userId);
//                        transaction.setStatus("PENDING");
//                        userTransactionRepository.save(transaction);
//                        userTransactionRepository.updateAmount(userId);
//                        transaction.setStatus("COMPLETED");
//                        userTransactionRepository.save(transaction);
//                    }
//                }
//                """;
//        Files.writeString(tempDir.resolve("TransactionService.java"), serviceCode);
//
//        TransactionTemplate template = new TransactionTemplate();
//        template.setMethodSignature("TransactionService.processTransaction");
//        template.setClassName("TransactionService");
//        template.setMethodName("processTransaction");
//        template.setPropagation("REQUIRED");
//        template.setReadOnly(false);
//        template.setSelfInvocation(false);
//        template.setCalledMethods(List.of());
//
//        SqlOperation updateAmountOp = new SqlOperation("UPDATE",
//                "UPDATE user_transaction SET amount = ? WHERE user_id = ?",
//                List.of("user_transaction"), "user_id = ?", "UserTransactionRepository.updateAmount");
//
//        Map<String, List<SqlOperation>> sqlMap = new HashMap<>();
//        sqlMap.put("UserTransactionRepository.updateAmount", List.of(updateAmountOp));
//        // No sqlMap entries for save — they must be generated
//
//        EntityModel entityModel = new EntityModel();
//        entityModel.getEntityToTable().put("UserTransaction", "user_transaction");
//        entityModel.getFieldToColumn().put("UserTransaction.userId", "user_id");
//        entityModel.getFieldToColumn().put("UserTransaction.status", "status");
//
//        CallGraphPropagator propagator = new CallGraphPropagator();
//        List<ResolvedTransaction> results = propagator.propagate(
//                List.of(template), sqlMap, entityModel, tempDir.toString());
//
//        ResolvedTransaction resolved = results.get(0);
//        List<SqlOperation> stmts = resolved.getOrderedStatements();
//
//        // Expected: INSERT (first save), UPDATE (updateAmount JPQL), UPDATE (second save)
//        assertTrue(stmts.size() >= 3,
//                "Expected at least 3 statements, got: " + stmts.size()
//                + " → " + stmts.stream().map(o -> o.getType() + ":" + o.getRawSql()).toList());
//        assertEquals("INSERT", stmts.get(0).getType(),
//                "First save on new object should be INSERT, got: "
//                + stmts.stream().map(o -> o.getType() + ":" + o.getRawSql()).toList());
//        assertEquals("UPDATE", stmts.get(1).getType(), "updateAmount should be UPDATE");
//        assertEquals("UPDATE", stmts.get(2).getType(),
//                "Second save on already-saved object should be UPDATE");
//    }
//
//    /**
//     * Test empty inputs produce empty results.
//     */
//    @Test
//    void testEmptyInputs() {
//        CallGraphPropagator propagator = new CallGraphPropagator();
//        List<ResolvedTransaction> results = propagator.propagate(
//                List.of(), Map.of(), new EntityModel());
//        assertTrue(results.isEmpty());
//    }
//}
