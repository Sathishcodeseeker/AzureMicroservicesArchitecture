package com.enterprise.eventhub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.retry.annotation.EnableRetry;

/**
 * Main Spring Boot Application for Event Hub with Outbox Pattern
 * 
 * 5W1H:
 * WHO: Enterprise applications requiring reliable event publishing
 * WHAT: Implements transactional outbox pattern with Azure Event Hub
 * WHEN: Used for mission-critical event-driven architectures
 * WHERE: Deployed in microservices handling high-volume transactions
 * WHY: Ensures eventual consistency and guaranteed delivery of events
 * HOW: Uses CDC (Change Data Capture) to pull from outbox table
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
@EnableRetry
public class EventHubOutboxApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(EventHubOutboxApplication.class, args);
    }
}
