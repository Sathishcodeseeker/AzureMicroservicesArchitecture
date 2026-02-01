package com.enterprise.eventhub.service;

import com.enterprise.eventhub.domain.entity.OutboxEvent;
import com.enterprise.eventhub.domain.entity.OutboxEvent.OutboxStatus;
import com.enterprise.eventhub.domain.event.OrderCreatedEvent;
import com.enterprise.eventhub.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for OutboxService
 * 
 * Tests:
 * 1. Event publishing success
 * 2. Event serialization
 * 3. Metadata creation
 * 4. Idempotency check
 * 5. Error handling
 */
@ExtendWith(MockitoExtension.class)
class OutboxServiceTest {
    
    @Mock
    private OutboxEventRepository outboxEventRepository;
    
    @Mock
    private ObjectMapper objectMapper;
    
    @InjectMocks
    private OutboxService outboxService;
    
    private OrderCreatedEvent testEvent;
    private OutboxEvent savedOutboxEvent;
    
    @BeforeEach
    void setUp() {
        testEvent = OrderCreatedEvent.builder()
            .eventType("OrderCreated")
            .orderId("order-123")
            .customerId("cust-456")
            .totalAmount(99.99)
            .currency("USD")
            .itemCount(3)
            .build();
        
        savedOutboxEvent = OutboxEvent.builder()
            .id(UUID.randomUUID())
            .aggregateId("order-123")
            .aggregateType("ORDER")
            .eventType("OrderCreated")
            .payload("{}")
            .status(OutboxStatus.PENDING)
            .retryCount(0)
            .createdAt(LocalDateTime.now())
            .updatedAt(LocalDateTime.now())
            .build();
    }
    
    @Test
    void testPublishEvent_Success() throws Exception {
        // Given
        String expectedJson = "{\"eventType\":\"OrderCreated\"}";
        when(objectMapper.writeValueAsString(any())).thenReturn(expectedJson);
        when(outboxEventRepository.save(any(OutboxEvent.class))).thenReturn(savedOutboxEvent);
        
        // When
        OutboxEvent result = outboxService.publishEvent(
            testEvent,
            "order-123",
            "ORDER"
        );
        
        // Then
        assertNotNull(result);
        assertEquals(OutboxStatus.PENDING, result.getStatus());
        assertEquals("order-123", result.getAggregateId());
        assertEquals("ORDER", result.getAggregateType());
        assertEquals("OrderCreated", result.getEventType());
        
        verify(objectMapper).writeValueAsString(testEvent);
        verify(outboxEventRepository).save(any(OutboxEvent.class));
    }
    
    @Test
    void testPublishEvent_EnrichesEvent() throws Exception {
        // Given
        testEvent.setEventId(null);
        testEvent.setCorrelationId(null);
        testEvent.setOccurredAt(null);
        
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        when(outboxEventRepository.save(any())).thenReturn(savedOutboxEvent);
        
        // When
        outboxService.publishEvent(testEvent, "order-123", "ORDER");
        
        // Then
        assertNotNull(testEvent.getEventId(), "Event ID should be generated");
        assertNotNull(testEvent.getCorrelationId(), "Correlation ID should be generated");
        assertNotNull(testEvent.getOccurredAt(), "Occurred at should be set");
        assertNotNull(testEvent.getVersion(), "Version should be set");
    }
    
    @Test
    void testPublishEvent_SerializationFailure() throws Exception {
        // Given
        when(objectMapper.writeValueAsString(any()))
            .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("Serialization error") {});
        
        // When & Then
        assertThrows(
            OutboxService.OutboxPublishException.class,
            () -> outboxService.publishEvent(testEvent, "order-123", "ORDER")
        );
        
        verify(outboxEventRepository, never()).save(any());
    }
    
    @Test
    void testEventExists_Found() {
        // Given
        when(outboxEventRepository.findByAggregateIdAndEventTypeAndStatus(
            "order-123", "OrderCreated", OutboxStatus.PENDING
        )).thenReturn(java.util.Optional.of(savedOutboxEvent));
        
        // When
        boolean exists = outboxService.eventExists("order-123", "OrderCreated");
        
        // Then
        assertTrue(exists);
    }
    
    @Test
    void testEventExists_NotFound() {
        // Given
        when(outboxEventRepository.findByAggregateIdAndEventTypeAndStatus(
            any(), any(), any()
        )).thenReturn(java.util.Optional.empty());
        
        // When
        boolean exists = outboxService.eventExists("order-999", "OrderCreated");
        
        // Then
        assertFalse(exists);
    }
}

/**
 * Unit Tests for CDC Polling Service
 */
@ExtendWith(MockitoExtension.class)
class OutboxCdcPollingServiceTest {
    
    @Mock
    private OutboxEventRepository outboxEventRepository;
    
    @Mock
    private EventHubPublisherService eventHubPublisher;
    
    @InjectMocks
    private OutboxCdcPollingService cdcPollingService;
    
    private OutboxEvent pendingEvent;
    
    @BeforeEach
    void setUp() {
        pendingEvent = OutboxEvent.builder()
            .id(UUID.randomUUID())
            .aggregateId("order-123")
            .aggregateType("ORDER")
            .eventType("OrderCreated")
            .payload("{\"orderId\":\"order-123\"}")
            .status(OutboxStatus.PENDING)
            .partitionKey("order-123")
            .retryCount(0)
            .createdAt(LocalDateTime.now())
            .updatedAt(LocalDateTime.now())
            .build();
    }
    
    @Test
    void testPollAndPublishEvents_NoPendingEvents() {
        // Given
        when(outboxEventRepository.findPendingEventsForProcessing(any(), any(), any()))
            .thenReturn(java.util.List.of());
        
        // When
        cdcPollingService.pollAndPublishEvents();
        
        // Then
        verify(eventHubPublisher, never()).publishBatch(any(), any());
    }
    
    @Test
    void testPollAndPublishEvents_Success() {
        // Given
        when(outboxEventRepository.findPendingEventsForProcessing(any(), any(), any()))
            .thenReturn(java.util.List.of(pendingEvent));
        when(eventHubPublisher.publishBatch(any(), any()))
            .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(1));
        
        // When
        cdcPollingService.pollAndPublishEvents();
        
        // Then
        verify(outboxEventRepository).save(argThat(event -> 
            event.getStatus() == OutboxStatus.PROCESSING
        ));
        verify(eventHubPublisher).publishBatch(any(), eq("order-123"));
    }
    
    @Test
    void testHandlePublishSuccess() {
        // Given
        java.util.List<OutboxEvent> events = java.util.List.of(pendingEvent);
        when(outboxEventRepository.bulkUpdateStatusToPublished(any(), any(), any()))
            .thenReturn(1);
        
        // When
        cdcPollingService.handlePublishSuccess(events);
        
        // Then
        verify(outboxEventRepository).bulkUpdateStatusToPublished(
            argThat(ids -> ids.contains(pendingEvent.getId())),
            any(),
            any()
        );
    }
    
    @Test
    void testHandlePublishFailure_WithRetriesRemaining() {
        // Given
        pendingEvent.setRetryCount(1);
        Throwable error = new RuntimeException("Publish failed");
        
        // When
        cdcPollingService.handlePublishFailure(
            java.util.List.of(pendingEvent),
            error
        );
        
        // Then
        verify(outboxEventRepository).save(argThat(event -> 
            event.getStatus() == OutboxStatus.FAILED &&
            event.getRetryCount() == 2 &&
            event.getNextRetryAt() != null
        ));
    }
    
    @Test
    void testHandlePublishFailure_MaxRetriesExceeded() {
        // Given
        pendingEvent.setRetryCount(3);  // Max retries
        Throwable error = new RuntimeException("Publish failed");
        
        // When
        cdcPollingService.handlePublishFailure(
            java.util.List.of(pendingEvent),
            error
        );
        
        // Then
        verify(outboxEventRepository).save(argThat(event -> 
            event.getStatus() == OutboxStatus.DEAD_LETTER
        ));
    }
}

/**
 * Unit Tests for Event Hub Publisher
 */
@ExtendWith(MockitoExtension.class)
class EventHubPublisherServiceTest {
    
    @Mock
    private EventHubProperties properties;
    
    private EventHubPublisherService publisherService;
    
    @BeforeEach
    void setUp() {
        EventHubProperties.Retry retry = new EventHubProperties.Retry();
        retry.setMaxRetries(3);
        retry.setBackoffDelayMs(1000L);
        retry.setMaxBackoffDelayMs(30000L);
        
        EventHubProperties.Backpressure backpressure = new EventHubProperties.Backpressure();
        backpressure.setMaxBatchSize(100);
        backpressure.setMaxWaitTimeSeconds(10);
        
        when(properties.getRetry()).thenReturn(retry);
        when(properties.getBackpressure()).thenReturn(backpressure);
        when(properties.getConnectionString()).thenReturn("Endpoint=sb://test.servicebus.windows.net/;");
        when(properties.getEventHubName()).thenReturn("test-events");
        
        publisherService = new EventHubPublisherService(properties);
    }
    
    @Test
    void testInitialize() {
        // When
        publisherService.initialize();
        
        // Then
        // Should not throw exception
        assertDoesNotThrow(() -> publisherService.initialize());
    }
}

/**
 * Unit Tests for Order Service
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {
    
    @Mock
    private OrderRepository orderRepository;
    
    @Mock
    private OutboxService outboxService;
    
    @InjectMocks
    private OrderService orderService;
    
    private OrderService.CreateOrderRequest validRequest;
    
    @BeforeEach
    void setUp() {
        validRequest = OrderService.CreateOrderRequest.builder()
            .customerId("cust-123")
            .totalAmount(99.99)
            .currency("USD")
            .itemCount(3)
            .shippingAddress("123 Main St")
            .userId("user-456")
            .build();
    }
    
    @Test
    void testCreateOrder_Success() {
        // Given
        Order savedOrder = Order.builder()
            .id("order-123")
            .customerId("cust-123")
            .totalAmount(99.99)
            .currency("USD")
            .itemCount(3)
            .status("CREATED")
            .build();
        
        when(orderRepository.save(any(Order.class))).thenReturn(savedOrder);
        when(outboxService.publishEvent(any(), any(), any())).thenReturn(new OutboxEvent());
        
        // When
        Order result = orderService.createOrder(validRequest);
        
        // Then
        assertNotNull(result);
        assertEquals("CREATED", result.getStatus());
        assertEquals("cust-123", result.getCustomerId());
        
        verify(orderRepository).save(any(Order.class));
        verify(outboxService).publishEvent(any(), eq(result.getId()), eq("ORDER"));
    }
    
    @Test
    void testCreateOrder_ValidationFails() {
        // Given
        validRequest.setTotalAmount(-10.0);
        
        // When & Then
        assertThrows(
            OrderService.OrderValidationException.class,
            () -> orderService.createOrder(validRequest)
        );
        
        verify(orderRepository, never()).save(any());
        verify(outboxService, never()).publishEvent(any(), any(), any());
    }
    
    @Test
    void testCreateOrder_DatabaseFailure() {
        // Given
        when(orderRepository.save(any())).thenThrow(new RuntimeException("DB Error"));
        
        // When & Then
        assertThrows(
            OrderService.OrderCreationException.class,
            () -> orderService.createOrder(validRequest)
        );
        
        verify(outboxService, never()).publishEvent(any(), any(), any());
    }
}
