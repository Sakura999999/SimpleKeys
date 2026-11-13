package com.simplecard.service.impl;

import com.simplecard.common.PageResult;
import com.simplecard.constant.ErrorCode;
import com.simplecard.constant.OrderStatus;
import com.simplecard.entity.Order;
import com.simplecard.entity.PointsLog;
import com.simplecard.entity.User;
import com.simplecard.exception.BusinessException;
import com.simplecard.model.request.ChangePasswordRequest;
import com.simplecard.model.response.UserProfileResponse;
import com.simplecard.repository.OrderRepository;
import com.simplecard.repository.VerifiedPaymentEventRepository;
import com.simplecard.repository.PointsLogRepository;
import com.simplecard.repository.UserRepository;
import com.simplecard.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final VerifiedPaymentEventRepository paymentEvents;
    private final com.simplecard.service.InventoryLifecycleService inventoryLifecycle;
    private final PointsLogRepository pointsLogRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public UserProfileResponse getProfile(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在"));
        return UserProfileResponse.from(user);
    }

    @Override
    @Transactional
    public void changePassword(UUID userId, ChangePasswordRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在"));
        if (!passwordEncoder.matches(request.getOldPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.OLD_PASSWORD_WRONG, "原密码错误");
        }
        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        // 改密后作废此前签发的所有令牌 —— 否则密码泄露后改密并不能把攻击者踢下线
        user.setTokensInvalidBefore(
                java.time.LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        userRepository.save(user);
    }

    @Override
    public PageResult<?> getOrders(UUID userId, String status, int page, int pageSize) {
        PageRequest pageable = PageRequest.of(page - 1, pageSize);
        Page<Order> orderPage;
        if (status != null) {
            OrderStatus os;
            try {
                os = OrderStatus.valueOf(status);
            } catch (IllegalArgumentException e) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "无效的订单状态: " + status);
            }
            orderPage = orderRepository.findByUserIdAndStatusOrderByCreatedAtDesc(
                    userId, os, pageable);
        } else {
            orderPage = orderRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
        }

        // 主动过期检查：PENDING 且已超时的订单标记为 EXPIRED（与 queryOrders 逻辑一致）
        LocalDateTime now = LocalDateTime.now();
        for (Order o : orderPage.getContent()) {
            if (o.getStatus() == OrderStatus.PENDING && o.getExpiresAt().isBefore(now)) {
                try { inventoryLifecycle.close(o.getId(), false); }
                catch (com.simplecard.exception.BusinessException busy) {
                    if (busy.getHttpStatus() != org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE) throw busy;
                }
                o.setStatus(orderRepository.findById(o.getId()).orElseThrow().getStatus());
            }
        }

        var list = orderPage.getContent().stream().map(this::toOrderBrief).toList();
        return PageResult.of(orderPage, list);
    }

    @Override
    public Map<String, Object> getPoints(UUID userId, int page, int pageSize) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在"));
        PageRequest pageable = PageRequest.of(page - 1, pageSize);
        Page<PointsLog> logPage = pointsLogRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);

        Map<String, Object> result = new HashMap<>();
        result.put("total_points", user.getPoints());
        result.put("list", logPage.getContent());
        result.put("pagination", new PageResult.Pagination(page, pageSize, logPage.getTotalElements()));
        return result;
    }

    private Map<String, Object> toOrderBrief(Order o) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", o.getId());
        map.put("total_amount", o.getTotalAmount());
        map.put("actual_amount", o.getActualAmount());
        map.put("status", o.getStatus().name());
            map.put("payment_processing", paymentEvents.existsByOrderIdAndProcessedFalse(o.getId()));
        map.put("order_type", o.getOrderType().name());
        map.put("payment_method", o.getPaymentMethod());
        map.put("created_at", o.getCreatedAt());
        return map;
    }
}
