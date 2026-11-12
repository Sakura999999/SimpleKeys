package com.simplecard.controller;

import com.simplecard.common.ApiResponse;
import com.simplecard.constant.ErrorCode;
import com.simplecard.constant.OrderStatus;
import com.simplecard.context.RequestContext;
import com.simplecard.entity.Order;
import com.simplecard.exception.BusinessException;
import com.simplecard.repository.OrderRepository;
import com.simplecard.repository.UnmatchedTransactionRepository;
import com.simplecard.service.DeliverService;
import com.simplecard.service.OrderService;
import com.simplecard.service.TxidVerifyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final DeliverService deliverService;
    private final OrderRepository orderRepository;
    private final UnmatchedTransactionRepository unmatchedTransactionRepository;
    private final TxidVerifyService txidVerifyService;
    private final com.simplecard.service.PaymentService paymentService;
    private final com.simplecard.config.ClientIpResolver clientIpResolver;

    @PostMapping("/quote")
    public ApiResponse<?> quote(@RequestBody Map<String,Object> request,
                                @RequestHeader(value="X-Session-Token", required=false) String sessionToken) {
        return ApiResponse.success(orderService.quote(request,RequestContext.getUserId(),sessionToken));
    }

    @PostMapping
    public ApiResponse<?> createOrder(@RequestBody Map<String, Object> request,
                                      @RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
                                      HttpServletRequest httpRequest) {
        OrderService.CreatedOrder created = orderService.createDirectOrder(
                request, RequestContext.getUserId(), clientIpResolver.resolve(httpRequest), sessionToken);
        return ApiResponse.success(withPayment(created));
    }

    @PostMapping("/from-cart")
    public ApiResponse<?> createCartOrder(@RequestBody Map<String, Object> request,
                                          @RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
                                          HttpServletRequest httpRequest) {
        OrderService.CreatedOrder created = orderService.createCartOrder(
                request, RequestContext.getUserId(), clientIpResolver.resolve(httpRequest), sessionToken);
        return ApiResponse.success(withPayment(created));
    }

    /**
     * 建单事务提交后再调用支付网关，避免跨网络等待占用数据库连接。
     * 详见 {@link OrderService.CreatedOrder} 的说明。
     */
    private Map<String, Object> withPayment(OrderService.CreatedOrder created) {
        Map<String, Object> payment;
        try {
            payment = paymentService.createPayment(
                    created.orderId(), created.paymentMethod(), created.actualAmount(), created.device());
        } catch (RuntimeException e) {
            // 订单已独立提交，网关失败不会再连带回滚 —— 必须显式作废，
            // 否则这笔永远无法支付的 PENDING 订单会占用用户的未付款配额。
            orderService.markPaymentSetupFailed(created.orderId());
            throw e;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("order", created.orderDetail());
        result.put("payment", payment);
        return result;
    }

    @GetMapping("/{id}/status")
    public ApiResponse<?> getOrderStatus(@PathVariable UUID id) {
        return ApiResponse.success(orderService.getOrderStatus(id));
    }

    @PostMapping("/{id}/refresh")
    public ApiResponse<?> refreshOrderStatus(@PathVariable UUID id) {
        return ApiResponse.success(orderService.refreshOrderStatus(id));
    }

    @PostMapping("/query")
    public ApiResponse<?> queryOrders(@RequestBody Map<String, Object> request,
                                      @RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
        return ApiResponse.success(deliverService.queryOrders(request, sessionToken));
    }

    @PostMapping("/deliver")
    public ApiResponse<?> deliverOrders(@RequestBody Map<String, Object> request,
                                        @RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
        return ApiResponse.success(deliverService.deliverOrders(request, sessionToken));
    }

    @GetMapping("/{id}/export")
    public void exportCardKeys(@PathVariable UUID id,
                               @RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
                               HttpServletResponse response) throws Exception {
        String content = deliverService.exportCardKeys(id, sessionToken);
        response.setContentType("text/plain; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=card-keys-" + id + ".txt");
        response.getWriter().write(content);
    }

    /**
     * 重新发起支付（移动端支付取消/失败后重试）
     */
    @PostMapping("/{id}/repay")
    public ApiResponse<?> repayOrder(@PathVariable UUID id,
                                     @RequestBody(required = false) Map<String, String> body,
                                     @RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
        String device = body != null ? body.get("device") : null;
        return ApiResponse.success(paymentService.repay(id, device, sessionToken));
    }

    /**
     * 用户提交 TXID 进行自动链上验证（USDT 补单）
     */
    @PostMapping("/{id}/txid-verify")
    public ApiResponse<?> submitTxidForVerification(@PathVariable UUID id,
                                                     @RequestBody Map<String, String> request) {
        String txid = request.get("txid");
        if (txid == null || txid.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请提供交易哈希（TXID）");
        }
        txid = txid.trim();

        // 1. 校验订单存在
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "订单不存在"));

        // 2. 校验订单状态为 PENDING 或 EXPIRED
        if (order.getStatus() != OrderStatus.PENDING && order.getStatus() != OrderStatus.EXPIRED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "该订单状态不允许提交 TXID 验证");
        }

        // 3. 校验订单支付方式为 USDT
        if (order.getPaymentMethod() == null || !order.getPaymentMethod().startsWith("usdt_")) {
            throw new BusinessException(ErrorCode.ORDER_NOT_USDT, "该订单非 USDT 支付方式");
        }

        // 4. 校验 TXID 格式
        validateTxidFormat(txid, order.getUsdtChain());

        // 5. 校验 TXID 未被其他订单使用（同时检查 unmatched_transactions 表和 orders.usdt_tx_id 字段）
        if (unmatchedTransactionRepository.findByTxid(txid).isPresent()) {
            throw new BusinessException(ErrorCode.TXID_ALREADY_USED, "该交易哈希已被提交过");
        }
        if (orderRepository.findByUsdtTxId(txid).isPresent()) {
            throw new BusinessException(ErrorCode.TXID_ALREADY_USED, "该交易哈希已关联到其他订单");
        }

        // 6. 调用自动审核
        TxidVerifyService.VerifyDetail detail = txidVerifyService.verifyAndProcess(order, txid);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("result", detail.result().name());
        data.put("reason", detail.reason());
        return ApiResponse.success(data);
    }

    private void validateTxidFormat(String txid, String chain) {
        if (chain != null && chain.contains("trc20")) {
            // TRC20: 64 位 hex
            if (!txid.matches("^[a-fA-F0-9]{64}$")) {
                throw new BusinessException(ErrorCode.TXID_INVALID_FORMAT, "TXID 格式不正确，TRC-20 交易哈希应为 64 位十六进制字符");
            }
        } else if (chain != null && chain.contains("bep20")) {
            // BEP20: 0x 前缀 + 64 位 hex
            if (!txid.matches("^0x[a-fA-F0-9]{64}$")) {
                throw new BusinessException(ErrorCode.TXID_INVALID_FORMAT, "TXID 格式不正确，BEP-20 交易哈希应为 0x 开头的 66 位十六进制字符");
            }
        }
    }
}
