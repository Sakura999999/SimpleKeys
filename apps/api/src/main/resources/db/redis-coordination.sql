BEGIN;
CREATE TABLE IF NOT EXISTS verified_payment_events (
 id uuid PRIMARY KEY,
 event_key varchar(255) NOT NULL UNIQUE,
 channel_code varchar(255) NOT NULL,
 order_id uuid NOT NULL,
 tx_id varchar(255),
 processed boolean NOT NULL DEFAULT false,
 retry_count integer NOT NULL DEFAULT 0,
 next_attempt_at timestamp NOT NULL,
 last_error varchar(255),
 created_at timestamp,
 updated_at timestamp
);
CREATE INDEX IF NOT EXISTS idx_payment_events_due ON verified_payment_events(processed,next_attempt_at);
CREATE INDEX IF NOT EXISTS idx_payment_events_order ON verified_payment_events(order_id,processed);
COMMIT;
