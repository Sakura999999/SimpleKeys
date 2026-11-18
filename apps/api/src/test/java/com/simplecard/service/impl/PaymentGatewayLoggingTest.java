package com.simplecard.service.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplecard.exception.PaymentRejectedException;
import com.simplecard.service.BepusdtService;
import com.simplecard.service.EpayService;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PaymentGatewayLoggingTest {
    @Test
    void epayKeepsCheckoutLinksOutOfLogsAndDiscardsQueryErrorDetails() {
        RestTemplate http = new RestTemplate();
        var server = MockRestServiceServer.bindTo(http).build();
        var config = new EpayService.ChannelConfig("merchant", "synthetic-merchant-key", "https://example.invalid",
                "https://shop.invalid/notify", "https://shop.invalid/return");
        String checkoutUrl = "https://checkout.invalid?token=synthetic-checkout-token";
        server.expect(requestTo("https://example.invalid/mapi.php"))
                .andRespond(withSuccess("""
                        {"code":1,"msg":"synthetic-private-response","trade_no":"trade","payurl":"%s"}
                        """.formatted(checkoutUrl), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://example.invalid/api.php?act=order&pid=merchant&key=synthetic-merchant-key&out_trade_no=order"))
                .andRespond(withServerError().body("synthetic-private-query-response"));
        var epay = new EpayServiceImpl(http, new ObjectMapper());

        try (var logs = new DebugLogs(EpayServiceImpl.class)) {
            assertThat(epay.createPayment(config, "order", "alipay", "product", BigDecimal.ONE, null, "pc").payUrl())
                    .isEqualTo(checkoutUrl);
            assertThat(epay.queryOrder(config, "order")).isNull();
            assertThat(logs.text()).contains("order=order", "code=1", "InternalServerError")
                    .doesNotContain("synthetic-", "https://");
        }
        server.verify();
    }

    @Test
    void bepusdtKeepsProviderRejectionTextOutOfLogsAndPublicErrors() {
        RestTemplate http = new RestTemplate();
        var server = MockRestServiceServer.bindTo(http).build();
        var config = new BepusdtService.BepusdtConfig("https://example.invalid", "synthetic-api-token", "notify",
                "", "usdt.trc20", "CNY", 900, "");
        server.expect(requestTo("https://example.invalid/api/v1/order/create-transaction"))
                .andRespond(withSuccess("""
                        {"status_code":400,"message":"synthetic-api-token and synthetic-private-response"}
                        """, MediaType.APPLICATION_JSON));
        var bepusdt = new BepusdtServiceImpl(http, new ObjectMapper());

        try (var logs = new DebugLogs(BepusdtServiceImpl.class)) {
            assertThatThrownBy(() -> bepusdt.createPayment(config, "order", BigDecimal.ONE, "product"))
                    .isInstanceOf(PaymentRejectedException.class).hasMessageNotContaining("synthetic-");
            assertThat(logs.text()).contains("order=order", "code=400").doesNotContain("synthetic-", "https://");
        }
        server.verify();
    }

    private static final class DebugLogs implements AutoCloseable {
        private final Logger logger;
        private final Level previous;
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

        private DebugLogs(Class<?> source) {
            logger = (Logger) LoggerFactory.getLogger(source);
            previous = logger.getLevel();
            appender.start();
            logger.addAppender(appender);
            logger.setLevel(Level.DEBUG);
        }

        private String text() {
            assertThat(appender.list).allMatch(event -> event.getThrowableProxy() == null);
            return String.join("\n", appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
        }

        @Override
        public void close() {
            logger.setLevel(previous);
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
