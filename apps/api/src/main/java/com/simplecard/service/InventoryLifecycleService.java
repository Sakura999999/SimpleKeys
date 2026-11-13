package com.simplecard.service;

import com.simplecard.constant.*;
import com.simplecard.entity.*;
import com.simplecard.repository.*;
import com.simplecard.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.LocalDateTime;
import java.util.*;

/** Database is the inventory authority. All transitions lock order before card rows. */
@Service
@RequiredArgsConstructor
public class InventoryLifecycleService {
    private final OrderRepository orderRepository;
    private final VerifiedPaymentEventRepository paymentEvents;
    private final OrderItemRepository orderItemRepository;
    private final CardKeyRepository cardKeyRepository;
    private final UserRepository userRepository;
    private final PointsLogRepository pointsLogRepository;
    private final SiteConfigRepository siteConfigRepository;
    private final EmailService emailService;
    private final jakarta.persistence.EntityManager entityManager;

    @Transactional(timeout = 15)
    public void reserve(UUID id) {
        Order order = locked(id);
        if (order.getStatus() != OrderStatus.PENDING || order.getExpiresAt().isBefore(LocalDateTime.now()))
            throw new BusinessException(ErrorCode.ORDER_EXPIRED, "订单已过期");
        if (paymentEvents.existsByOrderIdAndProcessedFalse(id)) throw new BusinessException(ErrorCode.BAD_REQUEST,"付款已收到，正在处理，请查询订单");
        if (!allocate(order)) throw new BusinessException(ErrorCode.INSUFFICIENT_STOCK, "库存不足，请刷新后重试");
    }

    // Two phases: acquire ALL required rows before writing any new reservations.
    // selected prevents the same AVAILABLE row being reused for duplicate cart lines.
    private boolean allocate(Order order) {
        List<OrderItem> items = new ArrayList<>(orderItemRepository.findByOrderId(order.getId()));
        items.sort(Comparator.comparing((OrderItem i) -> i.getProductId().toString())
                .thenComparing(i -> Objects.toString(i.getSpecId(), "")).thenComparing(i -> i.getId().toString()));
        if (items.isEmpty() || items.stream().anyMatch(i -> i.getQuantity() < 1)) return false;
        List<CardKey> bound = cardKeyRepository.lockByOrderId(order.getId());
        Set<UUID> itemIds = new HashSet<>();
        items.forEach(i -> itemIds.add(i.getId()));
        if (bound.stream().anyMatch(k -> k.getStatus() != CardKeyStatus.LOCKED || !itemIds.contains(k.getOrderItemId()) || items.stream().noneMatch(i -> i.getId().equals(k.getOrderItemId()) && i.getProductId().equals(k.getProductId()) && Objects.equals(i.getSpecId(),k.getSpecId())))) return false;
        Map<OrderItem,List<CardKey>> allocation = new LinkedHashMap<>();
        Set<UUID> selected = new HashSet<>();
        for (OrderItem item : items) {
            List<CardKey> owned = bound.stream().filter(k -> k.getStatus() == CardKeyStatus.LOCKED
                    && item.getId().equals(k.getOrderItemId()) && item.getProductId().equals(k.getProductId())
                    && Objects.equals(item.getSpecId(), k.getSpecId())).toList();
            if (owned.size() > item.getQuantity()) return false;
            int missing = item.getQuantity() - owned.size();
            List<CardKey> keys = new ArrayList<>(owned);
            if (missing > 0) {
                List<CardKey> candidates = cardKeyRepository.findAndLockAvailable(item.getProductId(),item.getSpecId(),missing + selected.size());
                List<CardKey> fresh = candidates.stream().filter(k -> !selected.contains(k.getId())).limit(missing).toList();
                if (fresh.size() != missing) return false;
                keys.addAll(fresh);
                fresh.forEach(k -> selected.add(k.getId()));
            }
            allocation.put(item, keys);
        }
        for (var entry : allocation.entrySet()) for (CardKey key : entry.getValue()) {
            key.setStatus(CardKeyStatus.LOCKED);
            key.setOrderId(order.getId()); key.setOrderItemId(entry.getKey().getId());
            cardKeyRepository.save(key);
        }
        return true;
    }

    @Transactional(timeout = 15)
    public Order confirmPayment(UUID id, String txid) {
        Order order = locked(id);
        if (order.getStatus() == OrderStatus.REFUNDED || order.getStatus() == OrderStatus.DELIVERED) return order;
        if (txid != null) {
            if (order.getUsdtTxId() != null && !txid.equals(order.getUsdtTxId()))
                throw new BusinessException(ErrorCode.TXID_ALREADY_USED, "订单已关联其他交易");
            order.setUsdtTxId(txid);
        }
        if (order.getPaidAt() == null) order.setPaidAt(LocalDateTime.now());
        if (!allocate(order)) {
            order.setStatus(OrderStatus.PAID_REVIEW);
            order.setPaymentReviewReason("已核验付款，库存不足或预占异常，等待补货或人工退款");
            return orderRepository.saveAndFlush(order);
        }
        for (CardKey key : cardKeyRepository.lockByOrderId(id)) {
            if (key.getStatus() != CardKeyStatus.LOCKED) continue;
            key.setStatus(CardKeyStatus.SOLD); key.setSoldAt(LocalDateTime.now()); cardKeyRepository.save(key);
        }
        order.setStatus(OrderStatus.DELIVERED); order.setDeliveredAt(LocalDateTime.now());
        order.setPaymentReviewReason(null);
        orderRepository.saveAndFlush(order);
        awardPoints(order);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { emailService.sendDeliveryEmail(id); }
        });
        return order;
    }

    @Transactional(timeout = 15)
    public void close(UUID id, boolean force) {
        Order order = locked(id);
        if (order.getStatus() != OrderStatus.PENDING) return;
        if (paymentEvents.existsByOrderIdAndProcessedFalse(id)) return;
        if (!force && order.getExpiresAt().isAfter(LocalDateTime.now())) return;
        release(order);
        order.setStatus(OrderStatus.EXPIRED); orderRepository.save(order);
    }

    private void release(Order order) {
        for (CardKey key : cardKeyRepository.lockByOrderId(order.getId())) {
            if (key.getStatus() == CardKeyStatus.LOCKED) {
                key.setStatus(CardKeyStatus.AVAILABLE); key.setOrderId(null); key.setOrderItemId(null);
                cardKeyRepository.save(key);
            }
        }
    }

    @Transactional(timeout = 15)
    public void recordRefund(UUID id, String reference) {
        if (reference == null || reference.isBlank() || reference.length() > 250)
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请填写退款凭证（最多250字）");
        Order order = locked(id);
        if (order.getStatus() == OrderStatus.REFUNDED) return;
        if (order.getStatus() != OrderStatus.PAID_REVIEW)
            throw new BusinessException(ErrorCode.BAD_REQUEST, "仅已付款待处理订单可登记退款");
        release(order); order.setStatus(OrderStatus.REFUNDED); order.setRefundReference(reference);
        orderRepository.save(order);
    }

    @Transactional(timeout = 15)
    public void retryDelivery(UUID id) {
        Order o = locked(id);
        if (o.getStatus() != OrderStatus.PAID_REVIEW && o.getStatus() != OrderStatus.PAID)
            throw new BusinessException(ErrorCode.BAD_REQUEST,"仅已付款待处理订单可重试发货");
        confirmPayment(id, null);
    }

    @Transactional(timeout = 15)
    public void storePaymentDetails(Order snapshot) {
        Order o = locked(snapshot.getId());
        // Only update gateway fields; never merge a detached order status over a callback.
        o.setPaymentUrl(snapshot.getPaymentUrl()); o.setQrcodeUrl(snapshot.getQrcodeUrl());
        o.setEpayTradeNo(snapshot.getEpayTradeNo()); o.setUsdtWalletAddress(snapshot.getUsdtWalletAddress());
        o.setUsdtCryptoAmount(snapshot.getUsdtCryptoAmount()); o.setUsdtTradeId(snapshot.getUsdtTradeId());
        o.setUsdtChain(snapshot.getUsdtChain()); orderRepository.save(o);
    }

    @Transactional(timeout = 15)
    public void clearPaymentDetails(UUID id) {
        Order o = locked(id);
        if (o.getStatus() != OrderStatus.PENDING || o.getExpiresAt().isBefore(LocalDateTime.now()))
            throw new BusinessException(ErrorCode.ORDER_EXPIRED,"订单已过期或已支付");
        o.setPaymentUrl(null); o.setQrcodeUrl(null); o.setEpayTradeNo(null); orderRepository.save(o);
    }

    @Transactional(timeout = 15)
    public void lockRequest(String key) {
        if (key != null && entityManager.getEntityManagerFactory().getProperties().getOrDefault("hibernate.dialect", "").toString().contains("PostgreSQL")) {
            entityManager.createNativeQuery("set local lock_timeout = '5s'").executeUpdate();
            long hash = 0xcbf29ce484222325L;
            for (byte b : key.getBytes(java.nio.charset.StandardCharsets.UTF_8)) { hash ^= b & 255; hash *= 0x100000001b3L; }
            entityManager.createNativeQuery("select cast(pg_advisory_xact_lock(:key) as text)").setParameter("key",hash).getSingleResult();
        }
    }

    private Order locked(UUID id) {
        if (entityManager.getEntityManagerFactory().getProperties().getOrDefault("hibernate.dialect", "").toString().contains("PostgreSQL"))
            entityManager.createNativeQuery("set local lock_timeout = '5s'").executeUpdate();
        Order o = orderRepository.findByIdForUpdate(id).orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND,"订单不存在"));
        entityManager.refresh(o, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        return o;
    }
    private void awardPoints(Order order) {
        if (order.getUserId() == null) return;
        boolean pointsEnabled = siteConfigRepository.findByConfigKey("points_enabled")
                .map(c -> "true".equalsIgnoreCase(c.getConfigValue()))
                .orElse(false);
        if (!pointsEnabled) return;

        int pointsRate = siteConfigRepository.findByConfigKey("points_rate")
                .map(c -> { try { return Integer.parseInt(c.getConfigValue()); } catch (Exception e) { return 0; } })
                .orElse(0);
        if (pointsRate <= 0) return;

        int pointsEarned = order.getActualAmount().multiply(java.math.BigDecimal.valueOf(pointsRate))
                .setScale(0, java.math.RoundingMode.FLOOR).intValue();
        if (pointsEarned <= 0) return;

        // 加行锁读取 —— 同一用户的多笔订单并发发货时，无锁的读改写会丢失更新，
        // 且 PointsLog.balanceAfter 会与 users.points 实际值不一致。
        User user = userRepository.findByIdForUpdate(order.getUserId()).orElse(null);
        if (user == null) return;

        user.setPoints(user.getPoints() + pointsEarned);
        userRepository.save(user);

        PointsLog log = new PointsLog();
        log.setUserId(user.getId());
        log.setChangeAmount(pointsEarned);
        log.setBalanceAfter(user.getPoints());
        log.setReason("购物奖励积分");
        log.setOrderId(order.getId());
        pointsLogRepository.save(log);
    }
}
