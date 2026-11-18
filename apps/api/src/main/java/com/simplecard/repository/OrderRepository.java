package com.simplecard.repository;

import com.simplecard.constant.OrderStatus;
import com.simplecard.constant.OrderType;
import com.simplecard.entity.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    /**
     * 按邮箱列表 + 归属条件查订单（带会话兜底）。
     *
     * <p>邮箱比较用 {@code lower(...) in :emails}，调用方需传入已归一化的小写列表
     * （见 {@link com.simplecard.common.Emails#normalizeAll}）。此前用大小写敏感的
     * {@code in}，与访客查单路径的 {@code lower(=)} 语义不一致，导致混合大小写的历史数据漏单。
     */
    @Query("select o from Order o where lower(o.email) in :emails and (:admin=true or " +
            "(:userId is not null and o.userId=:userId) or (o.userId is null and :token is not null and o.sessionToken=:token))")
    List<Order> findAccessibleEmails(@Param("emails") java.util.Collection<String> emails,@Param("userId") UUID userId,
            @Param("token") String token,@Param("admin") boolean admin,org.springframework.data.domain.Pageable pageable);
    List<Order> findByStatusOrderByCreatedAtAsc(OrderStatus status);

    /**
     * 访客订单按邮箱查询 — 供邮箱验证码查单使用。
     *
     * <p>只匹配 {@code userId is null}：注册账号的订单必须登录本人查看，
     * 邮箱验证码不能成为绕过登录的旁路。邮箱比较用 lower() 以免大小写不同漏单。
     */
    @Query("select o from Order o where lower(o.email) = lower(:email) and o.userId is null "
            + "order by o.createdAt desc")
    List<Order> findGuestOrdersByEmail(@Param("email") String email, Pageable pageable);

    /** 该邮箱名下访客订单数 — 决定是否真的发出验证码邮件 */
    @Query("select count(o) from Order o where lower(o.email) = lower(:email) and o.userId is null")
    long countGuestOrdersByEmail(@Param("email") String email);

    /** 该邮箱名下属于注册账号的订单数 — 仅用于「另有 N 笔请登录查看」的提示，不返回内容 */
    @Query("select count(o) from Order o where lower(o.email) = lower(:email) and o.userId is not null")
    long countRegisteredOrdersByEmail(@Param("email") String email);

    Optional<Order> findByIdempotencyKey(String idempotencyKey);

    Optional<Order> findByUsdtTxId(String usdtTxId);

    boolean existsByUsdtTxIdIgnoreCaseAndIdNot(String usdtTxId, UUID id);

    Page<Order> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Page<Order> findByUserIdAndStatusOrderByCreatedAtDesc(UUID userId, OrderStatus status, Pageable pageable);

    List<Order> findByEmailInOrderByCreatedAtDesc(List<String> emails);

    List<Order> findByIdIn(List<UUID> ids);

    @Query("SELECT o FROM Order o WHERE o.status = com.simplecard.constant.OrderStatus.PENDING AND o.expiresAt < :now")
    List<Order> findExpiredOrders(@Param("now") LocalDateTime now);

    @Query("""
            select o from Order o where o.paidAt is null and o.expiresAt >= :since
            and o.status in :statuses and (o.epayTradeNo is not null or o.usdtTradeId is not null)
            and not exists (select a.id from PaymentAttempt a where a.orderId = o.id)
            order by o.expiresAt desc, o.id
            """)
    List<Order> findLegacyPayments(LocalDateTime since, java.util.Collection<OrderStatus> statuses, Pageable page);

    long countByUserIdAndStatus(UUID userId, OrderStatus status);

    long countByClientIpAndStatus(String clientIp, OrderStatus status);

    /**
     * 同邮箱待支付订单数（防刷）。
     *
     * <p>用 {@code lower(o.email) = lower(:email)} 而不是派生方法的大小写敏感匹配：
     * 换个大写字母就能绕过"同邮箱最多 N 笔待支付订单"的限制，等于限流形同虚设。
     */
    @Query("select count(o) from Order o where lower(o.email) = lower(:email) and o.status = :status")
    long countByEmailAndStatus(@Param("email") String email, @Param("status") OrderStatus status);

    /** 悲观写锁：SELECT ... FOR UPDATE，用于防止并发发货等竞态条件 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") UUID id);

    @Query("SELECT o FROM Order o WHERE o.riskFlagged = true ORDER BY o.createdAt DESC")
    Page<Order> findRiskFlaggedOrders(Pageable pageable);

    // Dashboard aggregate queries
    @Query("SELECT COALESCE(SUM(o.actualAmount), 0) FROM Order o WHERE (o.status = com.simplecard.constant.OrderStatus.PAID_REVIEW OR o.status = com.simplecard.constant.OrderStatus.PAID OR o.status = com.simplecard.constant.OrderStatus.DELIVERED) AND o.paidAt >= :since")
    BigDecimal sumSalesSince(@Param("since") LocalDateTime since);

    @Query("SELECT COUNT(o) FROM Order o WHERE (o.status = com.simplecard.constant.OrderStatus.PAID_REVIEW OR o.status = com.simplecard.constant.OrderStatus.PAID OR o.status = com.simplecard.constant.OrderStatus.DELIVERED) AND o.paidAt >= :since")
    long countPaidOrdersSince(@Param("since") LocalDateTime since);

    @Query("SELECT COUNT(o) FROM Order o WHERE (o.status = com.simplecard.constant.OrderStatus.PAID_REVIEW OR o.status = com.simplecard.constant.OrderStatus.PAID OR o.status = com.simplecard.constant.OrderStatus.DELIVERED)")
    long countTotalPaidOrders();

    /**
     * 取区间内的成交时间与金额，仅两列，不装载实体。
     *
     * <p>销售趋势图只需要「哪一天、多少钱」，此前用 {@code findAll()} 把整张订单表
     * 连同所有字段读进持久化上下文再内存过滤。10 万订单的基准是 543~697 ms、
     * 92 MB 堆，而这条查询的代价只与时间区间内的订单数有关。
     *
     * <p>返回 {@code Object[]{LocalDateTime paidAt, BigDecimal amount}}。
     * 用 {@code o.paidAt is not null} 显式排除未支付记录，避免调用方再判空。
     */
    @Query("select o.paidAt, o.actualAmount from Order o " +
            "where o.status in :statuses and o.paidAt is not null and o.paidAt >= :start and o.paidAt < :end")
    List<Object[]> findPaidAtAndAmountBetween(@Param("statuses") java.util.Collection<OrderStatus> statuses,
                                              @Param("start") LocalDateTime start,
                                              @Param("end") LocalDateTime end);

    long count();

    // 管理后台订单列表 — 无搜索词
    @Query("SELECT o FROM Order o WHERE " +
            "(:status IS NULL OR o.status = :status) " +
            "AND (:orderType IS NULL OR o.orderType = :orderType) " +
            "AND (:paymentMethod IS NULL OR o.paymentMethod = :paymentMethod) " +
            "AND (:isRiskFlagged IS NULL OR o.riskFlagged = :isRiskFlagged) " +
            "ORDER BY o.createdAt DESC")
    Page<Order> findAdminOrders(@Param("status") OrderStatus status,
                                @Param("orderType") OrderType orderType,
                                @Param("paymentMethod") String paymentMethod,
                                @Param("isRiskFlagged") Boolean isRiskFlagged,
                                Pageable pageable);

    // 管理后台订单列表 — 带搜索词（按订单ID、邮箱或商品名称搜索，keyword 保证非 null）
    @Query("SELECT DISTINCT o FROM Order o LEFT JOIN OrderItem oi ON oi.orderId = o.id WHERE " +
            "(:status IS NULL OR o.status = :status) " +
            "AND (:orderType IS NULL OR o.orderType = :orderType) " +
            "AND (:paymentMethod IS NULL OR o.paymentMethod = :paymentMethod) " +
            "AND (:isRiskFlagged IS NULL OR o.riskFlagged = :isRiskFlagged) " +
            "AND (str(o.id) LIKE :keywordPattern OR o.email LIKE :keywordPattern OR oi.productTitle LIKE :keywordPattern) " +
            "ORDER BY o.createdAt DESC")
    Page<Order> findAdminOrdersByKeyword(@Param("status") OrderStatus status,
                                         @Param("orderType") OrderType orderType,
                                         @Param("paymentMethod") String paymentMethod,
                                         @Param("isRiskFlagged") Boolean isRiskFlagged,
                                         @Param("keywordPattern") String keywordPattern,
                                         Pageable pageable);
}
