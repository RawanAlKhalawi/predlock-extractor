package com.predlock.extractor.phase2;

import com.predlock.extractor.model.TransactionTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestPhase2 {

    private final TransactionBoundaryExtractor extractor = new TransactionBoundaryExtractor();

    @Test
    void testExtractTransactionalMethod(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("OrderService.java"),
                """
                import org.springframework.stereotype.Service;
                import org.springframework.transaction.annotation.Transactional;
                import org.springframework.transaction.annotation.Propagation;

                @Service
                public class OrderService {
                    @Transactional(propagation = Propagation.REQUIRED, readOnly = false)
                    public void placeOrder(Long userId) {
                        inventoryRepo.findByProductId(1L);
                        orderRepo.save(order);
                    }
                }
                """);

        List<TransactionTemplate> results = extractor.extract(tempDir.toString());
        assertEquals(1, results.size());

        TransactionTemplate t = results.get(0);
        assertEquals("OrderService.placeOrder", t.getMethodSignature());
        assertEquals("OrderService", t.getClassName());
        assertEquals("placeOrder", t.getMethodName());
        assertEquals("REQUIRED", t.getPropagation());
        assertEquals("DEFAULT", t.getIsolation());
        assertFalse(t.isReadOnly());
        assertFalse(t.isSelfInvocation());
        assertEquals(List.of("inventoryRepo.findByProductId", "orderRepo.save"), t.getCalledMethods());
    }

    @Test
    void testDefaultAttributes(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("MyService.java"),
                """
                import org.springframework.stereotype.Service;
                import org.springframework.transaction.annotation.Transactional;

                @Service
                public class MyService {
                    @Transactional
                    public void doWork() {
                        repo.save(entity);
                    }
                }
                """);

        List<TransactionTemplate> results = extractor.extract(tempDir.toString());
        assertEquals(1, results.size());

        TransactionTemplate t = results.get(0);
        assertEquals("REQUIRED", t.getPropagation());
        assertEquals("DEFAULT", t.getIsolation());
        assertFalse(t.isReadOnly());
    }

    @Test
    void testClassLevelTransactional(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("AccountService.java"),
                """
                import org.springframework.stereotype.Service;
                import org.springframework.transaction.annotation.Transactional;

                @Service
                @Transactional(readOnly = true)
                public class AccountService {
                    public void getBalance() {
                        repo.findById(1L);
                    }
                    public void getHistory() {
                        repo.findAll();
                    }
                }
                """);

        List<TransactionTemplate> results = extractor.extract(tempDir.toString());
        assertEquals(2, results.size());

        for (TransactionTemplate t : results) {
            assertTrue(t.isReadOnly());
            assertEquals("AccountService", t.getClassName());
        }
    }

    @Test
    void testSelfInvocation(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("PaymentService.java"),
                """
                import org.springframework.stereotype.Service;
                import org.springframework.transaction.annotation.Transactional;

                @Service
                public class PaymentService {
                    @Transactional
                    public void processPayment() {
                        validate();
                    }

                    @Transactional
                    public void validate() {
                        repo.check();
                    }
                }
                """);

        List<TransactionTemplate> results = extractor.extract(tempDir.toString());
        assertEquals(2, results.size());

        TransactionTemplate process = results.stream()
                .filter(t -> t.getMethodName().equals("processPayment"))
                .findFirst().orElseThrow();
        assertTrue(process.isSelfInvocation());

        TransactionTemplate validate = results.stream()
                .filter(t -> t.getMethodName().equals("validate"))
                .findFirst().orElseThrow();
        assertFalse(validate.isSelfInvocation());
    }

    @Test
    void testNonServiceClassIgnored(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("PlainClass.java"),
                """
                import org.springframework.transaction.annotation.Transactional;

                public class PlainClass {
                    @Transactional
                    public void doSomething() {
                        repo.save(x);
                    }
                }
                """);

        List<TransactionTemplate> results = extractor.extract(tempDir.toString());
        assertTrue(results.isEmpty());
    }

    @Test
    void testEmptyDirectory(@TempDir Path tempDir) {
        List<TransactionTemplate> results = extractor.extract(tempDir.toString());
        assertTrue(results.isEmpty());
    }
}
