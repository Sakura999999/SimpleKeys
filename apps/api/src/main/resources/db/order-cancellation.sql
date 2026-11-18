-- Apply before starting an API version that supports CANCELLED. Preserves all orders.
BEGIN;
ALTER TABLE orders DROP CONSTRAINT IF EXISTS orders_status_check;
ALTER TABLE orders ADD CONSTRAINT orders_status_check CHECK (status IN ('PENDING','PAID','DELIVERED','EXPIRED','CANCELLED','PAID_REVIEW','REFUNDED'));
COMMIT;
