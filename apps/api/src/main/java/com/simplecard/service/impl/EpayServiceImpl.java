package com.simplecard.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplecard.constant.ErrorCode;
import com.simplecard.exception.BusinessException;
import com.simplecard.service.EpayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class EpayServiceImpl implements EpayService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public EpayResult createPayment(ChannelConfig config, String outTradeNo, String type, String name, BigDecimal money, String clientIp, String device) {
        // 动态拼接 return_url：基础 URL + orderId 查询参数，使 epay 回跳到订单查询页
        String baseReturnUrl = config.returnUrl();
        String dynamicReturnUrl = baseReturnUrl + (baseReturnUrl.contains("?") ? "&" : "?") + "orderId=" + outTradeNo;

        Map<String, String> params = new LinkedHashMap<>();
        params.put("pid", config.pid());
        params.put("type", type);
        params.put("out_trade_no", outTradeNo);
        params.put("notify_url", config.notifyUrl());
        params.put("return_url", dynamicReturnUrl);
        params.put("name", name);
        params.put("money", money.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString());
        params.put("clientip", clientIp != null ? clientIp : "127.0.0.1");
        params.put("device", device != null && !device.isBlank() ? device : "pc");

        String sign = buildSign(config.key(), params);
        params.put("sign", sign);
        params.put("sign_type", "MD5");

        log.info("Epay createPayment: order={}, type={}, amount={}", outTradeNo, type, money);

        // Build form-urlencoded body
        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        params.forEach(formData::add);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(formData, headers);

        String url = config.apiUrl() + (config.apiUrl().endsWith("/") ? "" : "/") + "mapi.php";

        // Without a gateway idempotency guarantee, a failed transport leaves an unknown outcome.
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            String responseBody = response.getBody();

            if (responseBody == null || responseBody.isBlank()) {
                log.error("Epay API returned null/empty body");
                throw new BusinessException(ErrorCode.WEBHOOK_VERIFY_FAIL, "支付创建失败：响应为空");
            }

            Map<String, Object> body;
            try {
                body = objectMapper.readValue(responseBody, new TypeReference<>() {});
            } catch (Exception parseEx) {
                log.warn("Epay create response was not valid JSON: order={}", outTradeNo);
                throw new BusinessException(ErrorCode.WEBHOOK_VERIFY_FAIL, "支付创建失败：响应格式异常");
            }

            int code = body.get("code") instanceof Number n ? n.intValue() : -1;
            String tradeNo = body.get("trade_no") != null ? body.get("trade_no").toString() : "";
            String payUrl = body.get("payurl") != null ? body.get("payurl").toString() : null;
            String qrcode = body.get("qrcode") != null ? body.get("qrcode").toString() : null;
            String urlscheme = body.get("urlscheme") != null ? body.get("urlscheme").toString() : null;

            log.info("Epay create response: order={}, code={}", outTradeNo, code);

            if (code != 1) {
                // 业务错误不重试
                throw new com.simplecard.exception.PaymentRejectedException(ErrorCode.WEBHOOK_VERIFY_FAIL, "支付平台拒绝了建单请求");
            }

            String resultQrcode = qrcode != null ? qrcode : urlscheme;

            // 网关未返回 payUrl 且为移动端请求时，将 qrcode（收银台页面 URL）作为 H5 跳转入口
            String effectivePayUrl = payUrl;
            if (effectivePayUrl == null && device != null && !"pc".equals(device) && resultQrcode != null) {
                effectivePayUrl = resultQrcode;
            }

            return new EpayResult(code, "", tradeNo, effectivePayUrl, resultQrcode);

        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            log.warn("Epay create outcome unknown: order={}, error={}", outTradeNo, exception.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE, "支付创建结果待核实，请查询原订单");
        }
    }

    @Override
    public String buildSign(String merchantKey, Map<String, String> params) {
        // 1. Filter out sign, sign_type, and empty values
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if ("sign".equals(key) || "sign_type".equals(key)) continue;
            if (value == null || value.isEmpty()) continue;
            sorted.put(key, value);
        }

        // 2. Concatenate sorted params: a=b&c=d&e=f
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            if (!sb.isEmpty()) sb.append('&');
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }

        // 3. Append merchant key directly
        sb.append(merchantKey);

        // 4. MD5 hash
        return md5(sb.toString());
    }

    @Override
    public OrderQueryResult queryOrder(ChannelConfig config, String outTradeNo) {
        String url = config.apiUrl() + (config.apiUrl().endsWith("/") ? "" : "/")
                + "api.php?act=order&pid=" + config.pid() + "&key=" + config.key()
                + "&out_trade_no=" + outTradeNo;

        try {
            ResponseEntity<String> response;
            String host = java.net.URI.create(config.apiUrl()).getHost();
            boolean ypay = "www.ezfpy.cn".equalsIgnoreCase(host) || "ezfpy.cn".equalsIgnoreCase(host);
            if (ypay) {
                // This provider indexes our out_trade_no under type=2, despite its public docs.
                MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
                form.add("order_no", outTradeNo);
                form.add("type", "2");
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
                response = restTemplate.postForEntity(config.apiUrl().replaceAll("/+$", "") + "/api/findorder",
                        new HttpEntity<>(form, headers), String.class);
            } else {
                response = restTemplate.getForEntity(url, String.class);
            }
            String body = response.getBody();
            if (body == null || body.isBlank()) {
                log.warn("Epay order query returned empty body: outTradeNo={}", outTradeNo);
                return null;
            }
            Map<String, Object> result = objectMapper.readValue(body, new TypeReference<>() {});

            Object rawCode = result.get("code");
            if (rawCode != null && !"1".equals(rawCode.toString()) && !"200".equals(rawCode.toString())) {
                log.warn("Epay query rejected: order={}", outTradeNo);
                return null;
            }
            if (ypay) {
                if (!(result.get("data") instanceof Map<?, ?> data)) return null;
                result = new LinkedHashMap<>();
                for (var entry : data.entrySet()) if (entry.getKey() instanceof String k) result.put(k, entry.getValue());
            }
            String merchantOrder = Objects.toString(result.get("out_trade_no"), null);
            String merchant = Objects.toString(result.get("pid"), null);
            if ((ypay || merchantOrder != null) && !outTradeNo.equals(merchantOrder)) return null;
            if (merchant != null && !config.pid().equals(merchant)) return null;
            boolean merchantVerified = outTradeNo.equals(merchantOrder) && (merchant != null || !ypay);

            String tradeStatus = result.get("status") != null ? result.get("status").toString() : null;
            String money = result.get("money") != null ? result.get("money").toString() : null;
            String tradeNo = result.get("trade_no") != null ? result.get("trade_no").toString() : null;
            return new OrderQueryResult(tradeStatus, money, tradeNo, merchantVerified);
        } catch (Exception e) {
            // The exception may embed the request URL (including the merchant key) or response content.
            log.warn("Epay order query failed: order={}, error={}", outTradeNo, e.getClass().getSimpleName());
            return null;
        }
    }

    @Override
    public boolean verifySign(String merchantKey, Map<String, String> params, String sign) {
        if (sign == null || sign.isEmpty()) return false;
        String expected = buildSign(merchantKey, params);
        // 恒定时间比较：签名是「伪造入账」的唯一门槛，不能留计时侧信道。
        // 仍保持大小写不敏感，网关回传大写 hex 时必须照旧通过。
        return com.simplecard.common.Signatures.equalsIgnoreCaseConstantTime(expected, sign);
    }

    private String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 algorithm not available", e);
        }
    }
}
