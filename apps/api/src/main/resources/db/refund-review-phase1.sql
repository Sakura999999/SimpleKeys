-- Run with psql -v ON_ERROR_STOP=1 after draining ALL old API instances.
-- This is an explicit migration, not an automatic production bootstrap.
BEGIN;
SET LOCAL lock_timeout = '5s';
DO $$ BEGIN
  IF EXISTS (SELECT usdt_tx_id FROM orders WHERE usdt_tx_id IS NOT NULL GROUP BY usdt_tx_id HAVING count(*) > 1) THEN
    RAISE EXCEPTION 'Duplicate order TXIDs: investigate before migration';
  END IF;
  IF EXISTS (SELECT coalesce(payment_method,'legacy_unknown'),btrim(refund_reference) FROM orders
      WHERE status='REFUNDED' AND nullif(btrim(refund_reference),'') IS NOT NULL
      GROUP BY 1,2 HAVING count(*) > 1) THEN
    RAISE EXCEPTION 'Duplicate legacy refund references: investigate before migration';
  END IF;
  IF EXISTS (SELECT 1 FROM orders WHERE status='REFUNDED' AND (actual_amount IS NULL OR actual_amount <= 0)) THEN
    RAISE EXCEPTION 'Legacy refund amount missing: reconcile before migration';
  END IF;
END $$;
CREATE UNIQUE INDEX IF NOT EXISTS uq_orders_usdt_tx_id_phase1 ON orders(usdt_tx_id) WHERE usdt_tx_id IS NOT NULL;
CREATE TABLE IF NOT EXISTS refund_requests (
  id uuid PRIMARY KEY,
  order_id uuid NOT NULL REFERENCES orders(id),
  status varchar(255) NOT NULL CHECK (status IN ('PROCESSING','SUCCEEDED')),
  amount numeric(10,2) NOT NULL CHECK (amount > 0),
  currency varchar(255) NOT NULL,
  payment_method varchar(255), payment_amount numeric(10,2), payment_currency varchar(255),
  reason varchar(500) NOT NULL,
  requested_by uuid, confirmed_by uuid, reference varchar(250), confirmed_at timestamp,
  created_at timestamp, updated_at timestamp,
  CONSTRAINT uq_refund_order UNIQUE(order_id),
  CONSTRAINT uq_refund_channel_reference UNIQUE(payment_method,reference)
);
-- Legacy external completion time and operator are unknown; do not invent them.
INSERT INTO refund_requests(id,order_id,status,amount,currency,payment_method,payment_amount,payment_currency,reason,reference,created_at,updated_at)
SELECT gen_random_uuid(),id,'SUCCEEDED',actual_amount,coalesce(settlement_currency,'CNY'),coalesce(payment_method,'legacy_unknown'),
 payment_amount,payment_currency,'历史人工退款迁移；实际退款时间未知',nullif(btrim(refund_reference),''),now(),now()
FROM orders WHERE status='REFUNDED' ON CONFLICT(order_id) DO NOTHING;
COMMIT;
