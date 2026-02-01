-- Flyway Migration: V2__Create_Orders_Table.sql
-- Creates orders table for business data

CREATE TABLE IF NOT EXISTS orders (
    id VARCHAR(255) PRIMARY KEY,
    customer_id VARCHAR(255) NOT NULL,
    total_amount DECIMAL(19, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    item_count INTEGER NOT NULL,
    shipping_address VARCHAR(500) NOT NULL,
    status VARCHAR(50) NOT NULL,
    cancellation_reason VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Create indexes
CREATE INDEX idx_orders_customer ON orders(customer_id);
CREATE INDEX idx_orders_status ON orders(status);
CREATE INDEX idx_orders_created ON orders(created_at);

COMMENT ON TABLE orders IS 
    'Order business data - demonstrates transactional outbox pattern';
