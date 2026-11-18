BEGIN;

CREATE TABLE IF NOT EXISTS payment_attempts (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL UNIQUE,
    channel_id uuid NOT NULL,
    provider varchar(16) NOT NULL,
    gateway_url varchar(1024) NOT NULL,
    merchant_id varchar(255),
    amount numeric(10, 2) NOT NULL,
    currency varchar(8) NOT NULL,
    gateway_trade_id varchar(255),
    pending_tx_id varchar(255),
    state varchar(255) NOT NULL,
    expires_at timestamp NOT NULL,
    next_check_at timestamp,
    check_failures integer NOT NULL DEFAULT 0,
    last_check_result varchar(255),
    created_at timestamp,
    updated_at timestamp
);
CREATE INDEX IF NOT EXISTS idx_payment_attempt_due ON payment_attempts(next_check_at);
ALTER TABLE payment_attempts ADD COLUMN IF NOT EXISTS pending_tx_id varchar(255);

ALTER TABLE verified_payment_events ADD COLUMN IF NOT EXISTS review_reason varchar(255);

COMMIT;
