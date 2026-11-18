package com.simplecard.service.impl;

import com.simplecard.constant.CardKeyStatus;
import com.simplecard.constant.OrderStatus;
import com.simplecard.entity.Product;
import com.simplecard.entity.VisitStats;
import com.simplecard.repository.*;
import com.simplecard.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final CardKeyRepository cardKeyRepository;
    private final VisitStatsRepository visitStatsRepository;
    private final ProductSpecRepository productSpecRepository;

    @Override
    public Map<String, Object> getStats() {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime monthStart = LocalDate.now().withDayOfMonth(1).atStartOfDay();

        // Use aggregate queries instead of loading all orders
        BigDecimal todaySales = orderRepository.sumSalesSince(todayStart);
        BigDecimal monthSales = orderRepository.sumSalesSince(monthStart);
        long todayOrders = orderRepository.countPaidOrdersSince(todayStart);
        long monthOrders = orderRepository.countPaidOrdersSince(monthStart);
        VisitStats todayVisit = visitStatsRepository.findByVisitDate(LocalDate.now()).orElse(null);
        long todayUv = todayVisit != null ? todayVisit.getUv() : 0;

        // 电商标准转化率 = 今日成交订单数 / 今日 UV × 100%
        double conversionRate = todayUv > 0 ? (double) todayOrders / todayUv * 100 : 0;

        // Low stock products — 库存用一次分组查询取回，避免逐商品 count（1000 商品 = 1001 次往返）。
        // 分组查询只返回"有卡密"的商品，因此缺省值必须是 0：零库存商品恰恰最该出现在看板上。
        List<Product> products = productRepository.findActiveProducts();
        Map<UUID, Long> availableByProduct = new HashMap<>();
        for (Object[] row : cardKeyRepository.countGroupByProductAndStatus(CardKeyStatus.AVAILABLE)) {
            availableByProduct.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        List<Map<String, Object>> lowStock = new ArrayList<>();
        for (Product p : products) {
            long available = availableByProduct.getOrDefault(p.getId(), 0L);
            if (available <= p.getLowStockThreshold()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("product_id", p.getId());
                m.put("title", p.getTitle());
                m.put("available_stock", available);
                m.put("threshold", p.getLowStockThreshold());
                lowStock.add(m);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("today_sales", todaySales);
        result.put("month_sales", monthSales);
        result.put("today_orders", todayOrders);
        result.put("month_orders", monthOrders);
        result.put("conversion_rate", Math.round(conversionRate * 100.0) / 100.0);
        result.put("today_pv", todayVisit != null ? todayVisit.getPv() : 0);
        result.put("today_uv", todayVisit != null ? todayVisit.getUv() : 0);
        result.put("low_stock_products", lowStock);
        return result;
    }

    @Override
    public List<?> getSalesTrend(String period, String startDate, String endDate) {
        LocalDate start;
        LocalDate end;
        try {
            start = startDate != null ? LocalDate.parse(startDate) : LocalDate.now().minusDays(30);
            end = endDate != null ? LocalDate.parse(endDate) : LocalDate.now();
        } catch (java.time.format.DateTimeParseException e) {
            // 日期格式错误属于入参问题，应该是 400 而不是让 DateTimeParseException 冒成 500
            throw new com.simplecard.exception.BusinessException(
                    com.simplecard.constant.ErrorCode.BAD_REQUEST, "日期格式应为 yyyy-MM-dd");
        }

        LocalDateTime startDt = start.atStartOfDay();
        LocalDateTime endDt = end.plusDays(1).atStartOfDay();

        // 只取「时间 + 金额」两列，且由数据库做区间过滤。
        // 改造前是 findAll() + 内存过滤，成本与整张订单表成正比（10 万单 ≈ 92 MB 堆）。
        List<Object[]> rows = orderRepository.findPaidAtAndAmountBetween(PAID_STATUSES, startDt, endDt);

        Map<String, BigDecimal> salesMap = new TreeMap<>();
        Map<String, Integer> countMap = new TreeMap<>();

        for (Object[] row : rows) {
            LocalDateTime paidAt = (LocalDateTime) row[0];
            BigDecimal amount = (BigDecimal) row[1];
            if (paidAt == null) continue;
            String key;
            if ("monthly".equals(period)) {
                key = paidAt.toLocalDate().withDayOfMonth(1).toString().substring(0, 7);
            } else {
                key = paidAt.toLocalDate().toString();
            }
            salesMap.merge(key, amount != null ? amount : BigDecimal.ZERO, BigDecimal::add);
            countMap.merge(key, 1, Integer::sum);
        }

        return salesMap.entrySet().stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", e.getKey());
            m.put("sales_amount", e.getValue());
            m.put("order_count", countMap.getOrDefault(e.getKey(), 0));
            return m;
        }).collect(Collectors.toList());
    }

    /** 计入销售额的状态：审核中、已支付、已发货。与 sumSalesSince 的口径保持一致 */
    private static final List<OrderStatus> PAID_STATUSES =
            List.of(OrderStatus.PAID_REVIEW, OrderStatus.PAID, OrderStatus.DELIVERED);
}
