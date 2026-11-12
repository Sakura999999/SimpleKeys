package com.simplecard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplecard.constant.ErrorCode;
import com.simplecard.exception.BusinessException;
import com.simplecard.repository.CurrencyRepository;
import com.simplecard.repository.PaymentChannelRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.math.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Service @RequiredArgsConstructor
public class SettlementService {
    private final CurrencyRepository currencies;
    private final PaymentChannelRepository channels;
    private final ObjectMapper mapper;

    public record Source(String key, UUID productId, UUID specId, String title, String currency, int quantity, BigDecimal price) {}
    public record Line(String key, UUID productId, String title, String sourceCurrency, int quantity,
                       BigDecimal sourceUnitPrice, BigDecimal exchangeRate, BigDecimal unitPrice, BigDecimal subtotal) {}
    public record Quote(List<Line> items, BigDecimal totalAmount, String settlementCurrency,
                        String paymentCurrency, BigDecimal paymentAmount, String quoteToken) {}

    public Quote quote(List<Source> sources, String method) {
        if (sources.isEmpty()) throw new BusinessException(ErrorCode.CART_EMPTY,"没有可结算的商品");
        Map<String,BigDecimal> rates = new HashMap<>();
        java.util.function.Function<String,BigDecimal> rate = code -> rates.computeIfAbsent(code,this::rate);
        List<Line> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        StringBuilder signature = new StringBuilder(Objects.toString(method,""));
        for (Source source : sources) {
            String code = source.currency() == null ? "CNY" : source.currency().toUpperCase(Locale.ROOT);
            BigDecimal fx = rate.apply(code);
            if (source.quantity() <= 0 || source.price() == null || source.price().signum() <= 0)
                throw new BusinessException(ErrorCode.BAD_REQUEST,"商品价格或数量无效");
            // Round the converted unit price once, then multiply; stored unit and subtotal agree.
            BigDecimal unit = source.price().multiply(fx).setScale(2,RoundingMode.HALF_UP);
            BigDecimal subtotal = unit.multiply(BigDecimal.valueOf(source.quantity()));
            if (unit.signum() <= 0) throw new BusinessException(ErrorCode.BAD_REQUEST,"换算后商品单价低于最小支付金额");
            lines.add(new Line(source.key(),source.productId(),source.title(),code,source.quantity(),source.price(),fx,unit,subtotal));
            total = total.add(subtotal);
            signature.append('|').append(source.key()).append(':').append(source.specId()).append(':').append(code)
                    .append(':').append(source.quantity()).append(':').append(source.price().stripTrailingZeros().toPlainString())
                    .append(':').append(fx.stripTrailingZeros().toPlainString());
        }
        if (total.compareTo(new BigDecimal("99999999.99")) > 0) throw new BusinessException(ErrorCode.BAD_REQUEST,"订单金额超出限制");
        String fiat = "CNY";
        if (method != null && !method.isBlank()) {
            var channel = channels.findByChannelCodeAndIsDeleted(method,0).filter(c -> c.isEnabled())
                    .orElseThrow(() -> new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE,"支付渠道不可用"));
            if ("usdt".equals(channel.getProviderType())) {
                try { fiat = mapper.readTree(channel.getConfigData() == null ? "{}" : channel.getConfigData()).path("fiat").asText("CNY").toUpperCase(Locale.ROOT); }
                catch (Exception ex) { throw new BusinessException(ErrorCode.BAD_REQUEST,"USDT 渠道配置无效"); }
                if (!Set.of("CNY","USD").contains(fiat)) throw new BusinessException(ErrorCode.BAD_REQUEST,"USDT 渠道法币仅支持 CNY 或 USD");
            } else if (!"epay".equals(channel.getProviderType())) {
                throw new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE,"该支付提供商尚未实现");
            }
        }
        BigDecimal payment = total.divide(rate.apply(fiat),2,RoundingMode.HALF_UP);
        if (payment.signum() <= 0) throw new BusinessException(ErrorCode.BAD_REQUEST,"换算后支付金额过小");
        signature.append('|').append(fiat).append(':').append(payment.toPlainString());
        try {
            String token = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(signature.toString().getBytes(StandardCharsets.UTF_8)));
            return new Quote(lines,total,"CNY",fiat,payment,token);
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    public BigDecimal rate(String code) {
        if ("CNY".equals(code)) return BigDecimal.ONE;
        return currencies.findByCodeAndEnabled(code,true).map(c -> c.getRateToCny())
                .filter(r -> r.signum() > 0)
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST,"币种 " + code + " 尚未配置有效的人民币汇率，请联系客服"));
    }
}
