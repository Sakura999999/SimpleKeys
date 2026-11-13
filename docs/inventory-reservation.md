# Inventory reservation rollout

Card states: AVAILABLE -> LOCKED -> SOLD. LOCKED records bind order_id and order_item_id; their lifetime is the order expiry. Expiry releases only LOCKED records, never SOLD records.

Payment confirmation uses InventoryLifecycleService for all channels, TXID verification, admin approval and manual payment. Verification/network calls happen before the locked transaction. Duplicate payment events do not allocate again. A late verified payment attempts all-or-nothing allocation; failure commits PAID_REVIEW and paid_at rather than discarding the payment. Admin can retry after restocking or record an externally completed refund (REFUNDED). The refund endpoint records evidence only; it does not initiate a gateway refund.

## Deployment

1. Pause new orders and drain old API instances before switching versions. Back up the database.
2. Apply apps/api/src/main/resources/db/inventory-reservation.sql. It adds fields/indexes and extends the PostgreSQL enum check. Run it before accepting new payment confirmations.
3. Deploy all API replicas together. Do not mix versions with different allocation logic.
4. As administrator POST /api/admin/inventory-migration?dryRun=true to inspect old PENDING/PAID orders and orphaned LOCKED card IDs. No card plaintext is returned. Investigate orphaned reservations manually; they are never automatically released.
5. POST /api/admin/inventory-migration?dryRun=false to process old PAID orders first, then PENDING orders in creation order. Unpaid orders without sufficient stock are closed, not left payable. These legacy payments can still arrive and follow the late-payment policy. This operation can be retried. Keep ordering paused until reconciliation completes.
6. Resume orders. Review PAID_REVIEW orders regularly in admin orders. Use retry-delivery after restocking, or refund-record with the external refund transaction reference after refund success.

A payment gateway rejection explicitly releases its reservation. An ambiguous network timeout retains the reservation until normal expiry. A process crash after commit is recovered by subsequent callbacks/expiry jobs. Email is sent after database commit; email delivery is best effort and is not an exactly-once SMTP guarantee.

## Verification

Tests use a disposable local PostgreSQL, never the configured production DB:

    docker run -d --name simplecard-inventory-test -p 127.0.0.1:55439:5432 -e POSTGRES_USER=inventory_test -e POSTGRES_PASSWORD=inventory_test -e POSTGRES_DB=inventory_test postgres:18-alpine

Set INVENTORY_PG_TESTS=true and run mvn -f apps/api/pom.xml -Dtest=InventoryPostgresIntegrationTest test. The test database is recreated by Hibernate. Unset the flag to skip PostgreSQL tests in ordinary H2 test runs.

The tests cover the last-card race, duplicate request keys, cart rollback, duplicate payment, expiry release, payment/expiry races, late payment/restock, refund terminal state, prepayment export protection, and delayed gateway responses.

Local validation on JDK 25 used Maven options -Dmaven.compiler.proc=full -Dlombok.version=1.18.42 -Dnet.bytebuddy.experimental=true -Dspring.mail.host=localhost. The project default toolchain is Java 22.
