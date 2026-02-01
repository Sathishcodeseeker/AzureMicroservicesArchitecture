
-- V3__Create_Order_Read_Model.sql
-- CQRS read-model table.
-- Written only by OrderProjectionService. Never by the command path.

CREATE TABLE IF NOT EXISTS order_read_model (
    order_id             VARCHAR(255)   PRIMARY KEY,
    customer_id          VARCHAR(255)   NOT NULL,
    total_amount         DECIMAL(19,2)  NOT NULL,
    currency             VARCHAR(3)     NOT NULL,
    item_count           INTEGER        NOT NULL,
    shipping_address     VARCHAR(500)   NOT NULL,
    last_order_status    VARCHAR(50)    NOT NULL,
    cancellation_reason  VARCHAR(500),
    created_at           TIMESTAMP      NOT NULL,
    projected_at         TIMESTAMP      NOT NULL,
    last_correlation_id  VARCHAR(100)
);

CREATE INDEX idx_orm_customer ON order_read_model (customer_id);
CREATE INDEX idx_orm_status   ON order_read_model (last_order_status);
CREATE INDEX idx_orm_created  ON order_read_model (created_at);
