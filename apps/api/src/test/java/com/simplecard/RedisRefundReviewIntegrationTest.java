package com.simplecard;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

/** Repeat the same invariants with the production Redis transaction wrapper enabled. */
@EnabledIfEnvironmentVariable(named="REDIS_COORDINATION_TESTS", matches="true")
@SpringBootTest(properties={
    "spring.datasource.url=jdbc:postgresql://127.0.0.1:55439/inventory_test",
    "spring.datasource.username=inventory_test", "spring.datasource.password=inventory_test",
    "spring.datasource.driver-class-name=org.postgresql.Driver", "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
    "coordination.enabled=true", "coordination.address=redis://127.0.0.1:56379", "coordination.password=",
    "coordination.prefix=refund-review-test", "coordination.wait-ms=3000", "coordination.watchdog-ms=2000",
    "payment-inbox.worker-enabled=false", "spring.sql.init.mode=never", "spring.mail.host=localhost", "mail.enabled=false"})
class RedisRefundReviewIntegrationTest extends RefundReviewPostgresIntegrationTest {
    @org.springframework.beans.factory.annotation.Autowired com.simplecard.service.RedisOrderCoordinator coordination;

    @org.junit.jupiter.api.Test @Override
    void multiItemFailureRollsBackOrderAndReservations() {
        var key=stock(product); var other=product();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> coordination.execute(java.util.List.of(
                coordination.stockKey(product.getId(),null),coordination.stockKey(other.getId(),null)),
                () -> new org.springframework.transaction.support.TransactionTemplate(transactions).execute(tx -> {
                    var order=new com.simplecard.entity.Order(); order.setOrderType(com.simplecard.constant.OrderType.CART);
                    order.setExpiresAt(java.time.LocalDateTime.now().plusMinutes(15)); orders.saveAndFlush(order);
                    line(order,product,1); line(order,other,1); inventory.reserve(order.getId()); return null;
                }))).isInstanceOf(com.simplecard.exception.BusinessException.class);
        org.assertj.core.api.Assertions.assertThat(orders.count()).isZero();
        org.assertj.core.api.Assertions.assertThat(cards.findById(key.getId()).orElseThrow().getStatus())
                .isEqualTo(com.simplecard.constant.CardKeyStatus.AVAILABLE);
    }
}
