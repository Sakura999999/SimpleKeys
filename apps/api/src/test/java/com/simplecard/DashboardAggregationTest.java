package com.simplecard;

import com.simplecard.constant.CardKeyStatus;
import com.simplecard.constant.OrderStatus;
import com.simplecard.constant.OrderType;
import com.simplecard.entity.CardKey;
import com.simplecard.entity.Order;
import com.simplecard.entity.Product;
import com.simplecard.exception.BusinessException;
import com.simplecard.repository.CardKeyRepository;
import com.simplecard.repository.OrderRepository;
import com.simplecard.repository.ProductRepository;
import com.simplecard.service.DashboardService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 看板聚合口径测试。
 *
 * <p>这两处是从"全表读进内存"改成 SQL 聚合时最容易改坏的地方：
 * <ul>
 *   <li><b>低库存</b>：零库存商品必须仍然出现（分组查询天然不返回它们），
 *       阈值判定必须仍是 {@code available <= threshold}</li>
 *   <li><b>销售趋势</b>：只统计 PAID_REVIEW/PAID/DELIVERED 且 {@code paidAt} 非空，
 *       日期分桶按 {@code paidAt} 而不是 {@code createdAt}</li>
 * </ul>
 * 改聚合 SQL 前先跑这个类。
 */
@SpringBootTest(properties = "spring.mail.host=localhost")
@Transactional
class DashboardAggregationTest {

    @Autowired
    DashboardService dashboard;

    @Autowired
    ProductRepository products;

    @Autowired
    CardKeyRepository cardKeys;

    @Autowired
    OrderRepository orders;

    private Product product(String title, int lowStockThreshold) {
        Product p = new Product();
        p.setTitle(title);
        p.setBasePrice(new BigDecimal("10.00"));
        p.setCategoryId(UUID.randomUUID());
        p.setLowStockThreshold(lowStockThreshold);
        return products.save(p);
    }

    private void keys(Product p, int count) {
        for (int i = 0; i < count; i++) {
            CardKey key = new CardKey();
            key.setProductId(p.getId());
            key.setContent(p.getTitle() + "-KEY-" + i);
            key.setStatus(CardKeyStatus.AVAILABLE);
            cardKeys.save(key);
        }
    }

    private Order order(OrderStatus status, String amount, LocalDateTime paidAt) {
        Order o = new Order();
        o.setStatus(status);
        o.setOrderType(OrderType.DIRECT);
        o.setTotalAmount(new BigDecimal(amount));
        o.setActualAmount(new BigDecimal(amount));
        o.setPaidAt(paidAt);
        return orders.save(o);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> lowStock() {
        return (List<Map<String, Object>>) dashboard.getStats().get("low_stock_products");
    }

    private long availableOf(Product p) {
        return lowStock().stream()
                .filter(m -> p.getId().equals(m.get("product_id")))
                .map(m -> ((Number) m.get("available_stock")).longValue())
                .findFirst()
                .orElse(-1L);
    }

    @Test
    void zeroStockProductStillAppearsInLowStockList() {
        Product empty = product("零库存商品", 10);
        // 不插入任何卡密：分组查询不会返回它，代码必须按缺省 0 补齐

        assertThat(availableOf(empty)).isEqualTo(0L);
    }

    @Test
    void thresholdIsInclusiveAndHealthyStockIsExcluded() {
        Product low = product("低于阈值", 10);
        keys(low, 10);                 // 恰好等于阈值 → 仍算低库存
        Product healthy = product("库存充足", 10);
        keys(healthy, 200);            // 远高于阈值 → 不应出现

        assertThat(availableOf(low)).isEqualTo(10L);
        assertThat(availableOf(healthy)).isEqualTo(-1L);
    }

    @Test
    void lowStockCountsAcrossSpecs() {
        Product p = product("多规格商品", 3);
        keys(p, 2);                    // 不区分规格，全部计入

        assertThat(availableOf(p)).isEqualTo(2L);
    }

    @Test
    void salesTrendBucketsByPaidDateAndIgnoresUnpaid() {
        LocalDate today = LocalDate.now();
        LocalDateTime todayPaid = today.atTime(10, 0);

        order(OrderStatus.DELIVERED, "100.00", todayPaid);
        order(OrderStatus.PAID, "50.00", todayPaid);
        order(OrderStatus.PAID_REVIEW, "25.00", todayPaid);
        // 未支付：即使有金额也不能计入
        order(OrderStatus.PENDING, "999.00", null);
        // 已支付但不在查询区间
        order(OrderStatus.DELIVERED, "777.00", today.minusDays(10).atTime(9, 0));

        List<?> trend = dashboard.getSalesTrend("daily", today.toString(), today.toString());
        assertThat(trend).hasSize(1);

        Map<?, ?> row = (Map<?, ?>) trend.getFirst();
        assertThat(row.get("date")).isEqualTo(today.toString());
        assertThat((BigDecimal) row.get("sales_amount")).isEqualByComparingTo("175.00");
        assertThat(row.get("order_count")).isEqualTo(3);
    }

    @Test
    void monthlyTrendGroupsByMonth() {
        LocalDate today = LocalDate.now();
        LocalDateTime firstOfMonth = today.withDayOfMonth(1).atTime(12, 0);

        order(OrderStatus.DELIVERED, "10.00", firstOfMonth);
        order(OrderStatus.DELIVERED, "20.00", firstOfMonth.plusDays(1));

        List<?> trend = dashboard.getSalesTrend("monthly",
                today.withDayOfMonth(1).toString(), today.toString());
        assertThat(trend).hasSize(1);
        Map<?, ?> row = (Map<?, ?>) trend.getFirst();
        assertThat(row.get("date")).isEqualTo(firstOfMonth.toString().substring(0, 7));
        assertThat((BigDecimal) row.get("sales_amount")).isEqualByComparingTo("30.00");
        assertThat(row.get("order_count")).isEqualTo(2);
    }

    @Test
    void emptyRangeReturnsEmptyList() {
        LocalDate today = LocalDate.now();
        assertThat(dashboard.getSalesTrend("daily",
                today.minusDays(365).toString(), today.minusDays(360).toString())).isEmpty();
    }

    /** 日期格式错误必须是 400，而不是让 DateTimeParseException 冒成 500 */
    @Test
    void invalidDateIsBadRequest() {
        assertThatThrownBy(() -> dashboard.getSalesTrend("daily", "2026/01/01", null))
                .isInstanceOf(BusinessException.class);
    }
}
