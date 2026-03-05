package com.predlock.extractor.phase3;

import com.predlock.extractor.model.EntityModel;
import com.predlock.extractor.model.SqlOperation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TestPhase3 {

    private SqlQueryCollector collector;
    private EntityModel entityModel;

    @BeforeEach
    void setUp() {
        collector = new SqlQueryCollector();
        entityModel = new EntityModel();
        entityModel.getEntityToTable().put("Order", "orders");
        entityModel.getEntityToTable().put("Product", "product");
        entityModel.getFieldToColumn().put("Order.userId", "user_id");
        entityModel.getFieldToColumn().put("Order.orderId", "order_id");
    }

    @Test
    void testFormA_NativeQuery(@TempDir Path tempDir) throws IOException {
        String code = """
                import org.springframework.data.jpa.repository.Query;
                import org.springframework.data.jpa.repository.JpaRepository;
                public interface OrderRepository extends JpaRepository<Order, Long> {
                    @Query(value = "SELECT * FROM orders WHERE id = ?1", nativeQuery = true)
                    Object findByIdNative(Long id);
                }
                """;
        Files.writeString(tempDir.resolve("OrderRepository.java"), code);

        Map<String, List<SqlOperation>> result = collector.collect(tempDir.toString(), entityModel);

        assertTrue(result.containsKey("OrderRepository.findByIdNative"));
        SqlOperation op = result.get("OrderRepository.findByIdNative").get(0);
        assertEquals("SELECT", op.getType());
        assertEquals("SELECT * FROM orders WHERE id = ?1", op.getRawSql());
        assertTrue(op.getTables().contains("orders"));
        assertNotNull(op.getWhereCondition());
    }

    @Test
    void testFormB_JpqlQuery(@TempDir Path tempDir) throws IOException {
        String code = """
                import org.springframework.data.jpa.repository.Query;
                import org.springframework.data.jpa.repository.JpaRepository;
                public interface OrderRepository extends JpaRepository<Order, Long> {
                    @Query("SELECT o FROM Order o WHERE o.userId = :id")
                    Object findByUserIdJpql(Long id);
                }
                """;
        Files.writeString(tempDir.resolve("OrderRepository.java"), code);

        Map<String, List<SqlOperation>> result = collector.collect(tempDir.toString(), entityModel);

        assertTrue(result.containsKey("OrderRepository.findByUserIdJpql"));
        SqlOperation op = result.get("OrderRepository.findByUserIdJpql").get(0);
        assertEquals("SELECT", op.getType());
        assertTrue(op.getTables().contains("orders"));
        assertNotNull(op.getWhereCondition());
    }

    @Test
    void testFormC_DerivedQuery(@TempDir Path tempDir) throws IOException {
        String code = """
                import org.springframework.data.jpa.repository.JpaRepository;
                import java.util.List;
                public interface OrderRepository extends JpaRepository<Order, Long> {
                    List<Order> findByUserId(Long userId);
                }
                """;
        Files.writeString(tempDir.resolve("OrderRepository.java"), code);

        Map<String, List<SqlOperation>> result = collector.collect(tempDir.toString(), entityModel);

        assertTrue(result.containsKey("OrderRepository.findByUserId"));
        SqlOperation op = result.get("OrderRepository.findByUserId").get(0);
        assertEquals("SELECT", op.getType());
        assertTrue(op.getTables().contains("orders"));
        assertEquals("user_id = ?", op.getWhereCondition());
        assertTrue(op.getRawSql().contains("orders"));
    }

    @Test
    void testFormC_DeleteDerivedQuery(@TempDir Path tempDir) throws IOException {
        String code = """
                import org.springframework.data.jpa.repository.JpaRepository;
                public interface OrderRepository extends JpaRepository<Order, Long> {
                    void deleteByOrderId(Long orderId);
                }
                """;
        Files.writeString(tempDir.resolve("OrderRepository.java"), code);

        Map<String, List<SqlOperation>> result = collector.collect(tempDir.toString(), entityModel);

        assertTrue(result.containsKey("OrderRepository.deleteByOrderId"));
        SqlOperation op = result.get("OrderRepository.deleteByOrderId").get(0);
        assertEquals("DELETE", op.getType());
        assertEquals("order_id = ?", op.getWhereCondition());
    }

    @Test
    void testFormD_JdbcTemplate(@TempDir Path tempDir) throws IOException {
        String code = """
                import org.springframework.stereotype.Service;
                @Service
                public class ProductService {
                    private Object jdbcTemplate;
                    public void findProduct() {
                        jdbcTemplate.query("SELECT * FROM product WHERE id = ?", null);
                    }
                }
                """;
        Files.writeString(tempDir.resolve("ProductService.java"), code);

        Map<String, List<SqlOperation>> result = collector.collect(tempDir.toString(), entityModel);

        assertTrue(result.containsKey("ProductService.findProduct"));
        SqlOperation op = result.get("ProductService.findProduct").get(0);
        assertEquals("SELECT", op.getType());
        assertTrue(op.getTables().contains("product"));
    }

    @Test
    void testFormD_EntityManagerCreateQuery(@TempDir Path tempDir) throws IOException {
        String code = """
                import org.springframework.stereotype.Service;
                @Service
                public class OrderService {
                    private Object entityManager;
                    public void findNewOrders() {
                        entityManager.createQuery("FROM Order WHERE status = 'NEW'");
                    }
                }
                """;
        Files.writeString(tempDir.resolve("OrderService.java"), code);

        Map<String, List<SqlOperation>> result = collector.collect(tempDir.toString(), entityModel);

        assertTrue(result.containsKey("OrderService.findNewOrders"));
        SqlOperation op = result.get("OrderService.findNewOrders").get(0);
        assertEquals("SELECT", op.getType());
        assertTrue(op.getTables().contains("orders"));
    }

    @Test
    void testEmptyDirectory(@TempDir Path tempDir) {
        Map<String, List<SqlOperation>> result = collector.collect(tempDir.toString(), entityModel);
        assertTrue(result.isEmpty());
    }
}
