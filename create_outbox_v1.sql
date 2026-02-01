-- Flyway Migration: V1__Create_Outbox_Table.sql
-- Creates outbox_events table with all necessary indexes

-- Create outbox_events table
CREATE TABLE IF NOT EXISTS outbox_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_id VARCHAR(255) NOT NULL,
    aggregate_type VARCHAR(100) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count INTEGER NOT NULL DEFAULT 0,
    error_message TEXT,
    partition_key VARCHAR(255),
    correlation_id UUID,
    metadata TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP,
    next_retry_at TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0
);

-- Create indexes for efficient CDC queries
CREATE INDEX idx_outbox_status ON outbox_events(status);
CREATE INDEX idx_outbox_created ON outbox_events(created_at);
CREATE INDEX idx_outbox_event_type ON outbox_events(event_type);
CREATE INDEX idx_outbox_aggregate_id ON outbox_events(aggregate_id);
CREATE INDEX idx_outbox_next_retry ON outbox_events(next_retry_at) 
    WHERE next_retry_at IS NOT NULL;
CREATE INDEX idx_outbox_correlation ON outbox_events(correlation_id) 
    WHERE correlation_id IS NOT NULL;

-- Composite index for CDC polling query
CREATE INDEX idx_outbox_polling ON outbox_events(status, created_at) 
    WHERE status IN ('PENDING', 'FAILED');

-- Comment on table
COMMENT ON TABLE outbox_events IS 
    'Transactional outbox for guaranteed event delivery to Azure Event Hub';

-- Comment on key columns
COMMENT ON COLUMN outbox_events.aggregate_id IS 
    'Business entity ID (Order ID, User ID, etc.)';
COMMENT ON COLUMN outbox_events.status IS 
    'PENDING, PROCESSING, PUBLISHED, FAILED, DEAD_LETTER';
COMMENT ON COLUMN outbox_events.next_retry_at IS 
    'Scheduled time for next retry attempt (exponential backoff)';
