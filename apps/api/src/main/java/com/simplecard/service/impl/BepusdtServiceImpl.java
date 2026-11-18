package com.simplecard.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplecard.constant.ErrorCode;
import com.simplecard.exception.BusinessException;
import com.simplecard.service.BepusdtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class BepusdtServiceImpl implements BepusdtService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public BepusdtPaymentResult createPayment(BepusdtConfig config, String orderId, BigDecimal amount, String productName) {
        // 用 String map 计算签名（签名算法基于字符串值）
        Map<String, String> signParams = new TreeMap<>();
        signParams.put("order_id", orderId);
        signParams.put("amount", amount.stripTrailingZeros().toPlainString());
        signParams.put("notify_url", config.notifyUrl());
        signParams.put("redirect_url", config.redirectUrl());
        signParams.put("trade_type", config.tradeType());
        signParams.put("fiat", config.fiat());
        signParams.put("name", productName);
        signParams.put("timeout", String.valueOf(config.timeout()));
        if (config.fixedRate() != null && !config.fixedRate().isBlank()) {
            signParams.put("rate", config.fixedRate());
        }

        String signature = buildSign(config.apiToken(), signParams);

        // 构建请求体：amount/timeout 必须为数字类型（BEpusdt Go 端要求 float64/int64）
        Map<String, Object> requestBody = new TreeMap<>(signParams);
        requestBody.put("amount", amount.doubleValue());
        requestBody.put("timeout", (long) config.timeout());
        requestBody.put("signature", signature);

        String url = config.apiUrl();
        if (!url.endsWith("/")) url += "/";
        url += "api/v1/order/create-transaction";

        log.info("BEpusdt createPayment: order={}, amount={}, tradeType={}", orderId, amount, config.tradeType());

        // Without a gateway idempotency guarantee, a failed transport leaves an unknown outcome.
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> request = new HttpEntity<>(objectMapper.writeValueAsString(requestBody), headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            String responseBody = response.getBody();

            if (responseBody == null || responseBody.isBlank()) {
                log.error("BEpusdt API returned null/empty body");
                throw new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE, "USDT 支付创建失败：响应为空");
            }

            Map<String, Object> result;
            try {
                result = objectMapper.readValue(responseBody, new TypeReference<>() {});
            } catch (Exception parseEx) {
                log.warn("BEpusdt create response was not valid JSON: order={}", orderId);
                throw new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE, "USDT 支付创建失败：响应格式异常");
            }

            // BEpusdt 返回 status_code: 200 表示成功
            Object statusCode = result.get("status_code");
            int code = statusCode instanceof Number n ? n.intValue() : -1;
            if (code != 200) {
                log.warn("BEpusdt create rejected: order={}, code={}", orderId, code);
                throw new com.simplecard.exception.PaymentRejectedException(ErrorCode.CHANNEL_UNAVAILABLE, "USDT 支付平台拒绝了建单请求");
            }

            // 解析 data 字段
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get("data");
            if (data == null) {
                throw new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE, "USDT 支付创建失败：响应缺少 data");
            }

            String tradeId = getString(data, "trade_id");
            String walletAddress = getString(data, "token");
            String cryptoAmount = getString(data, "actual_amount");
            String paymentUrl = getString(data, "payment_url");
            String returnedOrderId = getString(data, "order_id");
            String returnedTradeType = getString(data, "trade_type");
            if (!orderId.equals(returnedOrderId) || tradeId == null || tradeId.isBlank()
                    || walletAddress == null || walletAddress.isBlank()
                    || paymentUrl == null || paymentUrl.isBlank()
                    || (!returnedTradeType.isBlank() && !config.tradeType().equals(returnedTradeType))
                    || !isPositiveAmount(cryptoAmount)) {
                throw new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE, "USDT 支付创建失败：网关响应不完整或与请求不匹配");
            }
            int expirationTime = data.get("expiration_time") instanceof Number n
                    ? n.intValue() : config.timeout();

            log.info("BEpusdt payment created: order={}", orderId);

            return new BepusdtPaymentResult(tradeId, walletAddress, cryptoAmount, paymentUrl, expirationTime);

        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            log.warn("BEpusdt create outcome unknown: order={}, error={}", orderId, exception.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE, "支付创建结果待核实，请查询原订单");
        }
    }

    @Override
    public OrderQueryResult queryOrder(BepusdtConfig config, String tradeId) {
        try {
            String url = config.apiUrl().replaceAll("/+$", "") + "/api/v1/pay/info";
            String response = restTemplate.postForObject(url, Map.of("trade_id", tradeId), String.class);
            if (response == null) return null;
            var root = objectMapper.readTree(response);
            var data = root.path("data");
            if (root.path("status_code").asInt() != 200 || !data.isObject()) return null;
            String txid = data.path("block_transaction_id").asText("");
            if (txid.isBlank()) {
                // The checkout API returns an explorer URL. Extract the ID locally and never fetch that URL.
                String explorer = "usdt.bep20".equals(config.tradeType())
                        ? "https://bscscan[.]com/tx/(?:0[xX])?" : "https://tronscan[.]org/#/transaction/";
                var matcher = java.util.regex.Pattern.compile(explorer + "([a-fA-F0-9]{64})")
                        .matcher(data.path("trade_url").asText(""));
                if (matcher.matches()) {
                    txid = ("usdt.bep20".equals(config.tradeType()) ? "0x" : "") + matcher.group(1);
                }
            }
            return new OrderQueryResult(data.path("status").asText(), data.path("order_id").asText(),
                    data.path("trade_id").asText(), data.path("trade_type").asText(),
                    data.path("token").asText(), data.path("actual_amount").asText(),
                    data.path("money").asText(), data.path("fiat").asText(), txid);
        } catch (Exception exception) {
            log.warn("BEpusdt order lookup unavailable: error={}", exception.getClass().getSimpleName());
            return null;
        }
    }

    private boolean isPositiveAmount(String value) {
        if (value == null) return false;
        try {
            return new BigDecimal(value).signum() > 0;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    /**
     * BEpusdt 签名算法（兼容易支付签名协议）：
     * 1. 取所有非空参数（排除 signature）
     * 2. 按 key 字母序排序
     * 3. 拼接 key1=value1&key2=value2&...
     * 4. 末尾直接拼接 apiToken（无 & 分隔符）
     * 5. MD5 哈希，小写
     */
    @Override
    public String buildSign(String apiToken, Map<String, String> params) {
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if ("signature".equals(key) || "sign".equals(key) || "sign_type".equals(key)) continue;
            if (value == null || value.isEmpty()) continue;
            sorted.put(key, value);
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            if (!sb.isEmpty()) sb.append('&');
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }

        // 末尾直接拼接 apiToken（无 & 分隔符）
        sb.append(apiToken);

        return md5(sb.toString());
    }

    @Override
    public boolean verifySign(String apiToken, Map<String, String> params, String signature) {
        if (signature == null || signature.isEmpty()) return false;
        String expected = buildSign(apiToken, params);
        // 与易支付保持一致：恒定时间比较，同时保留大小写不敏感
        return com.simplecard.common.Signatures.equalsIgnoreCaseConstantTime(expected, signature);
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

    private static String getString(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : "";
    }
}
