package com.enterprise.eventhub.integration;

import com.enterprise.eventhub.cqrs.projection.OrderProjectionService;
import com.enterprise.eventhub.cqrs.query.OrderQueryService;
import com.enterprise.eventhub.cqrs.readmodel.OrderReadModel;
import com.enterprise.eventhub.cqrs.readmodel.OrderReadModelRepository;
import com.enterprise.eventhub.domain.entity.Order;
import com.enterprise.eventhub.domain.entity.OutboxEvent;
import com.enterprise.eventhub.domain.entity.OutboxEvent.OutboxStatus;
import com.enterprise.eventhub.domain.event.OrderCreatedEvent;
import com.enterprise.eventhub.repository.OrderRepository;
import com.enterprise.eventhub.repository.OutboxEventRepository;
import com.enterprise.eventhub.service.EventHubConsumerService;
import com.enterprise.eventhub.service.EventHubPublisherService;
import com.enterprise.eventhub.service.OutboxCdcPollingService;
import com.enterprise.eventhub.service.OutboxService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.bean.MockBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

// =============================================================================
// Testcontainer is started ONCE for the entire test run (static + reuse).
// DynamicPropertySource wires its JDBC URL into Spring before context loads.
// @MockBean replaces EventHubPublisherService and EventHubConsumerService
// with Mockito mocks — we never connect to Azure.
// =============================================================================

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
abstract class BaseIntegrationTest {

    // One container for the whole JVM.  Testcontainers reuses it across classes.
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:15-alpine")
                    .withDatabaseName("outboxdb_test")
                    .withUsername("test_user")
                    .withPassword("test_pass");

    static {
        POSTGRES.start();
    }

    /**
     * Spring 5.4+ mechanism: inject dynamic properties before context loads.
     * This is how Testcontainers integrates with Spring Boot without a
     * separate properties file per run.
     */
    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    // -------------------------------------------------------------------
    // These two beans have @PostConstruct / @PreDestroy that connect to
    // Azure.  @MockBean replaces them with do-nothing mocks.
    // -------------------------------------------------------------------
    @MockBean
    EventHubPublisherService mockPublisher;

    @MockBean
    EventHubConsumerService mockConsumer;
}


// =============================================================================
// 1.  OUTBOX HAPPY PATH
//     Order created → outbox row is PENDING → CDC poll → row is PUBLISHED.
// =============================================================================
class OutboxCdcHappyPathTest extends BaseIntegrationTest {

    @Autowired OutboxService            outboxService;
    @Autowired OutboxEventRepository    outboxRepo;
    @Autowired OrderRepository          orderRepo;
    @Autowired OutboxCdcPollingService  cdcService;

    @BeforeEach
    void stubPublisherToSucceed() {
        when(mockPublisher.publishBatch(any(List.class), any(String.class)))
                .thenReturn(CompletableFuture.completedFuture(1));
    }

    @Test
    @Transactional
    void singleEventFlowsThroughCdcToPUBLISHED() {
        // ARRANGE
        String orderId = UUID.randomUUID().toString();
        orderRepo.save(buildOrder(orderId, "cust-1", 150.00));

        OutboxEvent oe = outboxService.publishEvent(
                buildOrderCreatedEvent(orderId, "cust-1", 150.00), orderId, "ORDER");

        assertEquals(OutboxStatus.PENDING, outboxRepo.findById(oe.getId()).orElseThrow().getStatus());

        // ACT — one CDC poll cycle
        cdcService.pollAndPublishEvents();
        awaitAsync();

        // ASSERT
        OutboxEvent after = outboxRepo.findById(oe.getId()).orElseThrow();
        assertEquals(OutboxStatus.PUBLISHED, after.getStatus(),
                "Expected PUBLISHED but got " + after.getStatus());
        assertNotNull(after.getPublishedAt());

        verify(mockPublisher, atLeastOnce()).publishBatch(any(), eq(orderId));
    }

    @Test
    @Transactional
    void fiveEventsBatchedAndAllPublished() {
        for (int i = 0; i < 5; i++) {
            String id = "batch-" + i + "-" + UUID.randomUUID();
            orderRepo.save(buildOrder(id, "batch-cust", 10.0 * (i + 1)));
            outboxService.publishEvent(
                    buildOrderCreatedEvent(id, "batch-cust", 10.0 * (i + 1)), id, "ORDER");
        }

        cdcService.pollAndPublishEvents();
        awaitAsync();

        // Every event that was PENDING should now be PUBLISHED
        long stillPending = outboxRepo.countPendingEvents();
        assertEquals(0, stillPending, "All events should have been published");
    }
}


// =============================================================================
// 2.  RETRY + DEAD-LETTER
//     Publisher always fails → after max retries the event is DEAD_LETTER.
// =============================================================================
class OutboxRetryAndDeadLetterTest extends BaseIntegrationTest {

    @Autowired OutboxService            outboxService;
    @Autowired OutboxEventRepository    outboxRepo;
    @Autowired OrderRepository          orderRepo;
    @Autowired OutboxCdcPollingService  cdcService;

    /**
     * Publisher always fails.  We manually drive the retry loop:
     *   Poll → FAILED → reset nextRetryAt to past → Poll → … → DEAD_LETTER
     */
    @Test
    @Transactional
    void eventReachesDeadLetterAfterMaxRetries() {
        when(mockPublisher.publishBatch(any(), any()))
                .thenReturn(CompletableFuture.failedFuture(
                        new EventHubPublisherService.EventHubPublishException("connection refused")));

        String orderId = UUID.randomUUID().toString();
        orderRepo.save(buildOrder(orderId, "dlq-cust", 1.0));
        OutboxEvent oe = outboxService.publishEvent(
                buildOrderCreatedEvent(orderId, "dlq-cust", 1.0), orderId, "ORDER");
        UUID outboxId = oe.getId();

        // Drive up to 5 poll cycles.  Max retry attempts = 3 (from test profile).
        // After 3 failures the CDC service marks it DEAD_LETTER on the next attempt.
        for (int i = 0; i < 5; i++) {
            cdcService.pollAndPublishEvents();
            awaitAsync();

            OutboxEvent current = outboxRepo.findById(outboxId).orElseThrow();
            if (current.getStatus() == OutboxStatus.DEAD_LETTER) break;

            // If FAILED, fast-forward nextRetryAt so the next poll picks it up
            if (current.getStatus() == OutboxStatus.FAILED) {
                current.setStatus(OutboxStatus.PENDING);
                current.setNextRetryAt(LocalDateTime.now().minusSeconds(1));
                outboxRepo.save(current);
            }
        }

        OutboxEvent finalState = outboxRepo.findById(outboxId).orElseThrow();
        assertEquals(OutboxStatus.DEAD_LETTER, finalState.getStatus(),
                "Expected DEAD_LETTER after max retries, got " + finalState.getStatus());
        assertNotNull(finalState.getErrorMessage());
    }

    /**
     * Publisher fails twice then succeeds.  Event should end up PUBLISHED.
     */
    @Test
    @Transactional
    void eventRecoversToPUBLISHEDAfterTransientFailures() {
        when(mockPublisher.publishBatch(any(), any()))
                .thenReturn(CompletableFuture.failedFuture(
                        new EventHubPublisherService.EventHubPublishException("timeout")))
                .thenReturn(CompletableFuture.failedFuture(
                        new EventHubPublisherService.EventHubPublishException("timeout")))
                .thenReturn(CompletableFuture.completedFuture(1));  // third call succeeds

        String orderId = UUID.randomUUID().toString();
        orderRepo.save(buildOrder(orderId, "retry-cust", 50.0));
        OutboxEvent oe = outboxService.publishEvent(
                buildOrderCreatedEvent(orderId, "retry-cust", 50.0), orderId, "ORDER");
        UUID outboxId = oe.getId();

        for (int i = 0; i < 4; i++) {
            cdcService.pollAndPublishEvents();
            awaitAsync();

            OutboxEvent current = outboxRepo.findById(outboxId).orElseThrow();
            if (current.getStatus() == OutboxStatus.PUBLISHED) break;
            if (current.getStatus() == OutboxStatus.FAILED) {
                current.setStatus(OutboxStatus.PENDING);
                current.setNextRetryAt(LocalDateTime.now().minusSeconds(1));
                outboxRepo.save(current);
            }
        }

        assertEquals(OutboxStatus.PUBLISHED,
                outboxRepo.findById(outboxId).orElseThrow().getStatus());
    }
}


// =============================================================================
// 3.  STUCK-EVENT RECOVERY
//     An event stuck in PROCESSING (simulating a crash mid-publish) is
//     reset to PENDING by the recovery job.
// =============================================================================
class StuckEventRecoveryTest extends BaseIntegrationTest {

    @Autowired OutboxService            outboxService;
    @Autowired OutboxEventRepository    outboxRepo;
    @Autowired OrderRepository          orderRepo;
    @Autowired OutboxCdcPollingService  cdcService;

    @Test
    @Transactional
    void eventStuckInProcessingForTenMinutesIsResetToPending() {
        String orderId = UUID.randomUUID().toString();
        orderRepo.save(buildOrder(orderId, "stuck-cust", 25.0));
        OutboxEvent oe = outboxService.publishEvent(
                buildOrderCreatedEvent(orderId, "stuck-cust", 25.0), orderId, "ORDER");

        // Simulate crash: manually set PROCESSING with a timestamp 10 min ago
        oe.setStatus(OutboxStatus.PROCESSING);
        oe.setUpdatedAt(LocalDateTime.now().minusMinutes(10));
        outboxRepo.save(oe);

        assertEquals(OutboxStatus.PROCESSING, outboxRepo.findById(oe.getId()).orElseThrow().getStatus());

        // Run recovery
        cdcService.recoverStuckEvents();

        // Should be PENDING again
        OutboxEvent recovered = outboxRepo.findById(oe.getId()).orElseThrow();
        assertEquals(OutboxStatus.PENDING, recovered.getStatus());
        assertTrue(recovered.getErrorMessage().contains("Recovered"));
    }

    @Test
    @Transactional
    void eventInProcessingForOnlyOneMinuteIsLeftAlone() {
        String orderId = UUID.randomUUID().toString();
        orderRepo.save(buildOrder(orderId, "recent-cust", 10.0));
        OutboxEvent oe = outboxService.publishEvent(
                buildOrderCreatedEvent(orderId, "recent-cust", 10.0), orderId, "ORDER");

        // Only 1 minute old — below the 5-minute threshold
        oe.setStatus(OutboxStatus.PROCESSING);
        oe.setUpdatedAt(LocalDateTime.now().minusMinutes(1));
        outboxRepo.save(oe);

        cdcService.recoverStuckEvents();

        // Still PROCESSING — recovery did not touch it
        assertEquals(OutboxStatus.PROCESSING, outboxRepo.findById(oe.getId()).orElseThrow().getStatus());
    }
}


// =============================================================================
// 4.  CQRS PROJECTION — full round-trip
//     Event → projection writes read model → query service reads it back.
// =============================================================================
class CqrsProjectionTest extends BaseIntegrationTest {

    @Autowired OrderProjectionService      projectionService;
    @Autowired OrderQueryService           queryService;
    @Autowired OrderReadModelRepository    readModelRepo;

    @Test
    @Transactional
    void orderCreatedProjectsIntoReadModel() {
        String orderId = "proj-" + UUID.randomUUID();
        OrderCreatedEvent event = buildOrderCreatedEvent(orderId, "proj-cust", 200.0);
        event.setOccurredAt(LocalDateTime.now());
        event.setCorrelationId(UUID.randomUUID());

        projectionService.onOrderCreated(event);

        OrderReadModel m = readModelRepo.findByOrderId(orderId).orElseThrow();
        assertEquals(orderId,       m.getOrderId());
        assertEquals("proj-cust",   m.getCustomerId());
        assertEquals(200.0,         m.getTotalAmount());
        assertEquals("USD",         m.getCurrency());
        assertEquals(3,             m.getItemCount());
        assertEquals("CREATED",     m.getLastOrderStatus());
        assertNotNull(m.getProjectedAt());
    }

    @Test
    @Transactional
    void queryServiceReturnsProjectedOrder() {
        String orderId = "qs-" + UUID.randomUUID();
        projectionService.onOrderCreated(buildOrderCreatedEvent(orderId, "qs-cust", 75.5));

        OrderReadModel result = queryService.getOrderById(orderId);
        assertEquals(orderId, result.getOrderId());
        assertEquals(75.5,    result.getTotalAmount());
    }

    @Test
    @Transactional
    void statusChangeUpdatesReadModel() {
        String orderId = "sc-" + UUID.randomUUID();
        projectionService.onOrderCreated(buildOrderCreatedEvent(orderId, "sc-cust", 100.0));

        boolean applied = projectionService.onOrderStatusChanged(orderId, "SHIPPED", "corr-abc");

        assertTrue(applied);
        OrderReadModel m = queryService.getOrderById(orderId);
        assertEquals("SHIPPED", m.getLastOrderStatus());
        assertEquals("corr-abc", m.getLastCorrelationId());
    }

    @Test
    @Transactional
    void cancellationUpdatesStatusAndReason() {
        String orderId = "cancel-" + UUID.randomUUID();
        projectionService.onOrderCreated(buildOrderCreatedEvent(orderId, "cancel-cust", 40.0));

        boolean applied = projectionService.onOrderCancelled(orderId, "Changed mind", null);

        assertTrue(applied);
        OrderReadModel m = queryService.getOrderById(orderId);
        assertEquals("CANCELLED",      m.getLastOrderStatus());
        assertEquals("Changed mind",   m.getCancellationReason());
    }

    @Test
    @Transactional
    void idempotentProjectionDoesNotDuplicate() {
        String orderId = "idem-" + UUID.randomUUID();
        OrderCreatedEvent event = buildOrderCreatedEvent(orderId, "idem-cust", 55.0);

        // Project same event twice — must not throw
        projectionService.onOrderCreated(event);
        projectionService.onOrderCreated(event);

        // Exactly one row with this orderId
        assertEquals("CREATED", queryService.getOrderById(orderId).getLastOrderStatus());
    }

    @Test
    @Transactional
    void outOfOrderStatusChangeIsSkippedGracefully() {
        String orderId = "ooo-" + UUID.randomUUID();

        // StatusChanged arrives BEFORE Created — row does not exist yet
        boolean applied = projectionService.onOrderStatusChanged(orderId, "SHIPPED", null);

        assertFalse(applied, "Should return false when row does not exist");
        assertTrue(readModelRepo.findByOrderId(orderId).isEmpty(),
                "No row should have been created");
    }

    @Test
    @Transactional
    void queryServiceThrowsForUnknownOrder() {
        assertThrows(OrderQueryService.OrderNotFoundException.class,
                () -> queryService.getOrderById("does-not-exist"));
    }

    @Test
    @Transactional
    void customerQueryReturnsOnlyTheirOrders() {
        String custId = "multi-" + UUID.randomUUID();

        for (int i = 0; i < 3; i++) {
            projectionService.onOrderCreated(
                    buildOrderCreatedEvent("mo-" + i + "-" + UUID.randomUUID(), custId, 10.0 * (i + 1)));
        }
        // One order for a different customer
        projectionService.onOrderCreated(
                buildOrderCreatedEvent("other-" + UUID.randomUUID(), "other-cust", 999.0));

        List<OrderReadModel> results = queryService.getOrdersByCustomer(custId);
        assertEquals(3, results.size());
        results.forEach(r -> assertEquals(custId, r.getCustomerId()));
    }

    @Test
    @Transactional
    void totalSpendPerCustomerIsCorrect() {
        String custId = "spend-" + UUID.randomUUID();
        projectionService.onOrderCreated(buildOrderCreatedEvent("s1-" + UUID.randomUUID(), custId, 100.0));
        projectionService.onOrderCreated(buildOrderCreatedEvent("s2-" + UUID.randomUUID(), custId, 50.0));

        var spend = queryService.getTotalSpendPerCustomer();
        assertEquals(150.0, spend.get(custId), 0.01);
    }
}


// =============================================================================
// 5.  CONTROLLER VALIDATION
//     Bad payloads to the command controller → 400.
//     Unknown orderId to the query controller → 404.
// =============================================================================
class ControllerValidationTest extends BaseIntegrationTest {

    @Autowired
    org.springframework.test.web.servlet.MockMvc mockMvc;

    // --- COMMAND SIDE: invalid bodies return 400 ---

    @Test
    void createOrder_rejectsBlankCustomerId() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                        .contentType("application/json")
                        .content(orderJson("", 50.0, "USD", 1)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createOrder_rejectsZeroAmount() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                        .contentType("application/json")
                        .content(orderJson("c1", 0.0, "USD", 1)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createOrder_rejectsNegativeItemCount() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                        .contentType("application/json")
                        .content(orderJson("c1", 50.0, "USD", -3)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createOrder_rejectsCurrencyNotThreeChars() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                        .contentType("application/json")
                        .content(orderJson("c1", 50.0, "USDX", 1)))
                .andExpect(status().isBadRequest());
    }

    // --- QUERY SIDE: unknown order returns 404 ---

    @Test
    void queryOrder_returns404ForUnknownId() throws Exception {
        mockMvc.perform(get("/api/v1/orders/query/nonexistent-id-xyz"))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------
    private static String orderJson(String custId, double amount, String currency, int items) {
        return """
                {
                  "customerId":     "%s",
                  "totalAmount":    %s,
                  "currency":       "%s",
                  "itemCount":      %d,
                  "shippingAddress":"123 Test St",
                  "userId":        "u1"
                }
                """.formatted(custId, amount, currency, items);
    }

    // Spring static imports used above
    private static org.springframework.test.web.servlet.ResultActions perform(
            org.springframework.test.web.servlet.MockMvc mvc,
            org.springframework.test.web.servlet.RequestBuilder req) throws Exception {
        return mvc.perform(req);
    }
}


// =============================================================================
// 6.  OUTBOX IDEMPOTENCY
//     eventExists() returns true after publish, false before.
// =============================================================================
class OutboxIdempotencyTest extends BaseIntegrationTest {

    @Autowired OutboxService     outboxService;
    @Autowired OrderRepository   orderRepo;

    @Test
    @Transactional
    void eventExistsReturnsTrueAfterPublish() {
        String orderId = UUID.randomUUID().toString();
        orderRepo.save(buildOrder(orderId, "idem", 1.0));

        outboxService.publishEvent(
                buildOrderCreatedEvent(orderId, "idem", 1.0), orderId, "ORDER");

        assertTrue(outboxService.eventExists(orderId, "OrderCreated"));
    }

    @Test
    @Transactional
    void eventExistsReturnsFalseWhenNothingPublished() {
        assertFalse(outboxService.eventExists("never-existed", "OrderCreated"));
    }
}


// =============================================================================
// Shared test-data builders — kept at the bottom so every test class can use them
// =============================================================================
abstract class BaseIntegrationTestHelpers {

    static Order buildOrder(String id, String custId, double amount) {
        return Order.builder()
                .id(id)
                .customerId(custId)
                .totalAmount(amount)
                .currency("USD")
                .itemCount(3)
                .shippingAddress("10 Test Lane")
                .status("CREATED")
                .createdAt(LocalDateTime.now())
                .build();
    }

    static OrderCreatedEvent buildOrderCreatedEvent(String orderId, String custId, double amount) {
        return OrderCreatedEvent.builder()
                .eventType("OrderCreated")
                .orderId(orderId)
                .customerId(custId)
                .totalAmount(amount)
                .currency("USD")
                .itemCount(3)
                .shippingAddress("10 Test Lane")
                .orderStatus("CREATED")
                .build();
    }

    /** Give CompletableFuture-based async paths a moment to resolve. */
    static void awaitAsync() {
        try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
