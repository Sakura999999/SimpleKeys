package com.simplecard.controller;

import com.simplecard.common.ApiResponse;
import com.simplecard.config.ClientIpResolver;
import com.simplecard.constant.ErrorCode;
import com.simplecard.exception.BusinessException;
import com.simplecard.service.OrderLookupGrantStore;
import com.simplecard.service.OrderLookupService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 访客订单找回 — 邮箱验证码接口。
 *
 * <p>路径刻意不放在 {@code /orders/**} 下：那里已有 {@code /orders/{id}/...} 一族变量路径，
 * 混在一起会让人误以为本接口也受订单号凭证保护。
 */
@RestController
@RequestMapping("/order-lookup")
@RequiredArgsConstructor
public class OrderLookupController {

    private final OrderLookupService orderLookupService;
    private final OrderLookupGrantStore grantStore;
    private final ClientIpResolver clientIpResolver;

    /** 请求验证码。无论该邮箱有无订单，响应完全一致。 */
    @PostMapping("/send")
    public ApiResponse<?> sendCode(@RequestBody(required = false) Map<String, Object> body,
                                   HttpServletRequest request) {
        String email = stringField(body, "email");
        String ip = clientIpResolver.resolve(request);

        OrderLookupService.SendResult result = orderLookupService.sendCode(email, ip);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("retry_after", result.retryAfterSeconds());
        return ApiResponse.success(data);
    }

    /** 校验验证码并签发查单凭证。 */
    @PostMapping("/verify")
    public ApiResponse<?> verifyCode(@RequestBody(required = false) Map<String, Object> body,
                                     HttpServletRequest request) {
        String email = stringField(body, "email");
        String code = stringField(body, "code");
        String ip = clientIpResolver.resolve(request);

        OrderLookupService.VerifyResult result = orderLookupService.verifyCode(email, code, ip);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", result.token());
        data.put("expires_at", result.expiresAtMillis());
        data.put("guest_orders", result.guestOrders());
        data.put("registered_orders", result.registeredOrders());
        return ApiResponse.success(data);
    }

    /** 主动作废当前凭证（例如用户在页面上点「结束验证」）。 */
    @PostMapping("/revoke")
    public ApiResponse<?> revoke(@RequestHeader(value = "X-Order-Lookup-Token", required = false) String token) {
        grantStore.revoke(token);
        return ApiResponse.success();
    }

    /** 取字符串字段并限制长度，避免把任意大对象塞进验证码比对路径 */
    private static String stringField(Map<String, Object> body, String field) {
        if (body == null) throw new BusinessException(ErrorCode.BAD_REQUEST, "请求体不能为空");
        Object raw = body.get(field);
        if (!(raw instanceof String value)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "参数 " + field + " 缺失");
        }
        if (value.length() > 320) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "参数 " + field + " 过长");
        }
        return value;
    }
}
