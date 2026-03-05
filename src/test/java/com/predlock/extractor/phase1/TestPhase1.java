package com.predlock.extractor.phase1;

import com.predlock.extractor.model.EntityModel;
import com.predlock.extractor.model.RelationshipInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Phase 1: EntityModelExtractor.
 */
class TestPhase1 {

    @Test
    void testExtractEntityModel(@TempDir Path tempDir) throws IOException {
        // Create a sample JPA entity file
        String orderEntity = """
                import javax.persistence.*;
                import java.util.List;

                @Entity
                @Table(name = "orders")
                public class Order {
                    @Column(name = "user_id")
                    private Long userId;

                    @OneToMany(fetch = FetchType.LAZY)
                    @JoinColumn(name = "order_id")
                    private List<OrderItem> items;
                }
                """;
        Files.writeString(tempDir.resolve("Order.java"), orderEntity);

        EntityModelExtractor extractor = new EntityModelExtractor();
        EntityModel model = extractor.extract(tempDir.toString());

        // entityToTable
        assertEquals("orders", model.getEntityToTable().get("Order"));

        // fieldToColumn
        assertEquals("user_id", model.getFieldToColumn().get("Order.userId"));

        // relationships
        RelationshipInfo rel = model.getRelationships().get("Order.items");
        assertNotNull(rel, "Relationship Order.items should exist");
        assertEquals("OrderItem", rel.getTargetEntity());
        assertEquals("order_id", rel.getJoinColumn());
        assertEquals("LAZY", rel.getFetchType());
        assertEquals("ONE_TO_MANY", rel.getRelationType());
    }

    @Test
    void testDefaultTableName(@TempDir Path tempDir) throws IOException {
        // Entity without @Table should default to lowercase class name
        String entity = """
                import javax.persistence.*;

                @Entity
                public class Customer {
                    private Long id;
                }
                """;
        Files.writeString(tempDir.resolve("Customer.java"), entity);

        EntityModelExtractor extractor = new EntityModelExtractor();
        EntityModel model = extractor.extract(tempDir.toString());

        assertEquals("customer", model.getEntityToTable().get("Customer"));
    }

    @Test
    void testManyToOneDefaultFetch(@TempDir Path tempDir) throws IOException {
        String entity = """
                import javax.persistence.*;

                @Entity
                @Table(name = "order_items")
                public class OrderItem {
                    @ManyToOne
                    @JoinColumn(name = "order_id")
                    private Order order;
                }
                """;
        Files.writeString(tempDir.resolve("OrderItem.java"), entity);

        EntityModelExtractor extractor = new EntityModelExtractor();
        EntityModel model = extractor.extract(tempDir.toString());

        RelationshipInfo rel = model.getRelationships().get("OrderItem.order");
        assertNotNull(rel);
        assertEquals("Order", rel.getTargetEntity());
        assertEquals("order_id", rel.getJoinColumn());
        assertEquals("EAGER", rel.getFetchType());
        assertEquals("MANY_TO_ONE", rel.getRelationType());
    }

    @Test
    void testFieldWithoutColumnAnnotation(@TempDir Path tempDir) throws IOException {
        String entity = """
                import javax.persistence.*;

                @Entity
                public class Product {
                    private String name;
                }
                """;
        Files.writeString(tempDir.resolve("Product.java"), entity);

        EntityModelExtractor extractor = new EntityModelExtractor();
        EntityModel model = extractor.extract(tempDir.toString());

        // Without @Column, field name is used as-is
        assertEquals("name", model.getFieldToColumn().get("Product.name"));
    }

    @Test
    void testEmptyDirectory(@TempDir Path tempDir) {
        EntityModelExtractor extractor = new EntityModelExtractor();
        EntityModel model = extractor.extract(tempDir.toString());

        assertTrue(model.getEntityToTable().isEmpty());
        assertTrue(model.getFieldToColumn().isEmpty());
        assertTrue(model.getRelationships().isEmpty());
    }
}
