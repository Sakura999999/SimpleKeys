-- Apply while old API instances are drained. Safe to rerun; never releases card inventory.
BEGIN;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS payment_review_reason varchar(255);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS refund_reference varchar(255);
ALTER TABLE orders DROP CONSTRAINT IF EXISTS orders_status_check;
ALTER TABLE orders ADD CONSTRAINT orders_status_check CHECK (status IN ('PENDING','PAID','DELIVERED','EXPIRED','PAID_REVIEW','REFUNDED'));
CREATE INDEX IF NOT EXISTS idx_orders_status_expiry ON orders(status,expires_at);
CREATE INDEX IF NOT EXISTS idx_keys_pool_status ON card_keys(product_id,spec_id,status,created_at);
CREATE INDEX IF NOT EXISTS idx_keys_order ON card_keys(order_id);
COMMIT;
