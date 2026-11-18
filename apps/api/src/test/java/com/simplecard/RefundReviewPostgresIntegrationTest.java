package com.simplecard;

import com.simplecard.constant.*;
import com.simplecard.entity.*;
import com.simplecard.repository.*;
import com.simplecard.service.*;
import com.simplecard.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.Callable;
import static org.assertj.core.api.Assertions.*;

/** Runs inherited inventory invariants plus refund/review races against disposable PostgreSQL. */
@EnabledIfEnvironmentVariable(named="INVENTORY_PG_TESTS", matches="true")
@SpringBootTest(properties={
    "spring.datasource.url=jdbc:postgresql://127.0.0.1:55439/inventory_test",
    "spring.datasource.username=inventory_test", "spring.datasource.password=inventory_test",
    "spring.datasource.driver-class-name=org.postgresql.Driver", "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
    "coordination.enabled=false", "payment-inbox.worker-enabled=false",
    "spring.sql.init.mode=never", "spring.mail.host=localhost", "mail.enabled=false"})
class RefundReviewPostgresIntegrationTest extends InventoryPostgresIntegrationTest {
    @Autowired RefundRequestRepository refunds;
    @Autowired UnmatchedTransactionRepository reviews;
    @Autowired TxidReviewService reviewService;
    @Autowired PaymentInboxService inbox;
    @Autowired VerifiedPaymentEventRepository events;

    UUID paidReview() { UUID id=pending(product,1); inventory.confirmPayment(id,null); return id; }
    UnmatchedTransaction review(UUID orderId) {
        var r=new UnmatchedTransaction(); r.setOrderId(orderId); r.setTxid(UUID.randomUUID().toString());
        r.setSubmittedAt(LocalDateTime.now()); return reviews.saveAndFlush(r);
    }
    boolean attempt(Runnable action) {
        try { action.run(); return true; } catch (BusinessException e) { return false; }
    }
    @Test void holdBlocksDeliveryAndVerifiedReceiptsWithoutLosingReceipt() {
        UUID id=paidReview(); inventory.requestRefund(id,"customer request"); stock(product);
        assertThatThrownBy(() -> inventory.retryDelivery(id)).isInstanceOf(BusinessException.class);
        inbox.accept("hold-receipt","test",id,null);
        assertThat(events.findByEventKey("hold-receipt").orElseThrow().isProcessed()).isTrue();
        assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID_REVIEW);
        assertThat(cards.findByOrderId(id)).isEmpty();
        assertThat(pointLogs.count()).isZero();
    }
    @Test void duplicateHoldAndConfirmationAreIdempotentButDifferentEvidenceIsRejected() {
        UUID id=paidReview(); var a=inventory.requestRefund(id,"reason");
        assertThat(inventory.requestRefund(id,"retry").getId()).isEqualTo(a.getId());
        inventory.recordRefund(id," ref-1 "); inventory.recordRefund(id,"ref-1");
        assertThat(refunds.count()).isEqualTo(1);
        var refund=refunds.findByOrderId(id).orElseThrow();
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(refund.getConfirmedAt()).isNotNull();
        assertThatThrownBy(() -> inventory.recordRefund(id,"other")).isInstanceOf(BusinessException.class);
    }
    @Test void refundRequiresHoldAndDeliveredOrdersRemainIneligible() {
        UUID id=paidReview();
        assertThatThrownBy(() -> inventory.recordRefund(id,"ref")).isInstanceOf(BusinessException.class);
        stock(product); inventory.retryDelivery(id);
        assertThatThrownBy(() -> inventory.requestRefund(id,"reason")).isInstanceOf(BusinessException.class);
        assertThat(cards.findByOrderId(id)).allMatch(k -> k.getStatus()==CardKeyStatus.SOLD);
    }
    @Test void failedConfirmationRollsBackOrderAndRefundRecord() {
        UUID a=paidReview(), b=paidReview();
        inventory.requestRefund(a,"a"); inventory.requestRefund(b,"b"); inventory.recordRefund(a,"same-ref");
        assertThatThrownBy(() -> inventory.recordRefund(b,"same-ref")).isInstanceOf(BusinessException.class);
        assertThat(orders.findById(b).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID_REVIEW);
        assertThat(refunds.findByOrderId(b).orElseThrow().getStatus()).isEqualTo(RefundStatus.PROCESSING);
    }
    @Test void holdAndRestockRaceHasExactlyOneWinner() throws Exception {
        UUID id=paidReview(); stock(product);
        var outcomes=parallel(() -> attempt(() -> inventory.requestRefund(id,"reason")),
                () -> attempt(() -> inventory.retryDelivery(id)));
        assertThat(outcomes).containsExactlyInAnyOrder(true,false);
        if (refunds.existsByOrderId(id)) {
            assertThat(cards.findByOrderId(id)).isEmpty();
            assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID_REVIEW);
        } else assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.DELIVERED);
    }
    @Test void duplicateApprovalsHaveOnlyOneSuccessfulCommit() throws Exception {
        UUID id=pending(product,1); stock(product); var r=review(id);
        Callable<Boolean> approve=() -> attempt(() -> reviewService.approve(r.getId()));
        assertThat(parallel(approve,approve)).containsExactlyInAnyOrder(true,false);
        assertThat(reviews.findById(r.getId()).orElseThrow().getStatus()).isEqualTo("APPROVED");
        assertThat(cards.findByOrderId(id)).hasSize(1);
    }
    @Test void approveAndRejectRaceHasOneConsistentOutcome() throws Exception {
        UUID id=pending(product,1); stock(product); var r=review(id);
        assertThat(parallel(() -> attempt(() -> reviewService.approve(r.getId())),
                () -> attempt(() -> reviewService.reject(r.getId(),"reject"))))
                .containsExactlyInAnyOrder(true,false);
        boolean approved=reviews.findById(r.getId()).orElseThrow().getStatus().equals("APPROVED");
        assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(approved?OrderStatus.DELIVERED:OrderStatus.PENDING);
        assertThat(cards.findByOrderId(id)).hasSize(approved?1:0);
    }
    @Test void ineligibleOrMissingOrderCannotSilentlyApprove() {
        for (UUID id : Arrays.asList(null,UUID.randomUUID(),paidReview())) {
            var r=review(id);
            assertThatThrownBy(() -> reviewService.approve(r.getId())).isInstanceOf(BusinessException.class);
            assertThat(reviews.findById(r.getId()).orElseThrow().getStatus()).isEqualTo("PENDING_REVIEW");
            assertThat(reviews.findById(r.getId()).orElseThrow().getReviewedAt()).isNull();
        }
    }
    @Test void approvalWithNoStockRecordsActualPaidReviewResult() {
        UUID id=pending(product,1); var r=review(id); reviewService.approve(r.getId());
        assertThat(reviews.findById(r.getId()).orElseThrow().getStatus()).isEqualTo("APPROVED");
        var o=orders.findById(id).orElseThrow();
        assertThat(o.getStatus()).isEqualTo(OrderStatus.PAID_REVIEW); assertThat(o.getUsdtTxId()).isEqualTo(r.getTxid());
    }
    @Test void conflictingTxidLeavesReviewAndOrderUntouched() {
        UUID existing=paidReview(), id=pending(product,1); var r=review(id);
        jdbc.update("update orders set usdt_tx_id=? where id=?",r.getTxid(),existing);
        assertThatThrownBy(() -> reviewService.approve(r.getId())).isInstanceOf(BusinessException.class);
        assertThat(reviews.findById(r.getId()).orElseThrow().getStatus()).isEqualTo("PENDING_REVIEW");
        assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING);
    }
    @Test void holdRetainsReservationsUntilRefundConfirmation() {
        UUID id=pending(product,2); var key=stock(product);
        key.setStatus(CardKeyStatus.LOCKED); key.setOrderId(id);
        key.setOrderItemId(items.findByOrderId(id).getFirst().getId()); cards.saveAndFlush(key);
        inventory.confirmPayment(id,null); inventory.requestRefund(id,"partial reservation");
        assertThat(cards.findById(key.getId()).orElseThrow().getStatus()).isEqualTo(CardKeyStatus.LOCKED);
        inventory.recordRefund(id,"release-after-confirmation");
        var released=cards.findById(key.getId()).orElseThrow();
        assertThat(released.getStatus()).isEqualTo(CardKeyStatus.AVAILABLE); assertThat(released.getOrderId()).isNull();
    }
    @Test void reviewWriteFailureRollsBackAlreadyChangedOrderAndCards() {
        UUID id=pending(product,1); stock(product); var r=review(id);
        jdbc.execute("create function phase1_review_failure() returns trigger language plpgsql as $$ begin raise exception 'test review write failure'; end $$");
        jdbc.execute("create trigger phase1_review_failure before update on unmatched_transactions for each row when (NEW.status='APPROVED') execute function phase1_review_failure()");
        try {
            assertThatThrownBy(() -> reviewService.approve(r.getId())).isInstanceOf(RuntimeException.class);
            assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING);
            assertThat(orders.findById(id).orElseThrow().getUsdtTxId()).isNull();
            assertThat(cards.findByOrderId(id)).isEmpty();
            assertThat(reviews.findById(r.getId()).orElseThrow().getStatus()).isEqualTo("PENDING_REVIEW");
        } finally {
            jdbc.execute("drop trigger phase1_review_failure on unmatched_transactions");
            jdbc.execute("drop function phase1_review_failure()");
        }
    }

}
