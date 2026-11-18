package com.simplecard.config;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 收件域路由的判定契约。
 *
 * <p>路由分错的后果是「国内信走海外通道」，代价是延迟与进垃圾箱，
 * 且从日志里看不出来 —— 只能靠这里的断言守住。
 */
class MailSenderRouterTest {

    private static MailChannelProperties.Channel channel(String host, String... domains) {
        MailChannelProperties.Channel c = new MailChannelProperties.Channel();
        c.setHost(host);
        c.setPort(465);
        c.setUsername("noreply@" + host);
        c.setPassword("secret");
        c.setDomains(List.of(domains));
        return c;
    }

    private static MailSenderRouter router(MailChannelProperties props) {
        MailSenderRouter router = new MailSenderRouter(props, legacyProvider(null), "", 465, "", "");
        router.build();
        return router;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<JavaMailSender> legacyProvider(JavaMailSender sender) {
        ObjectProvider<JavaMailSender> provider = Mockito.mock(ObjectProvider.class);
        Mockito.when(provider.getIfAvailable()).thenReturn(sender);
        return provider;
    }

    @Test
    void domesticDomainsGoToDomesticChannel() {
        MailChannelProperties props = new MailChannelProperties();
        props.setEnabled(true);
        props.getChannels().put("domestic", channel("smtp.domestic.test", "qq.com", "163.com"));
        props.getChannels().put("overseas", channel("smtp.overseas.test"));

        MailSenderRouter router = router(props);

        assertThat(router.route("buyer@qq.com").name()).isEqualTo("domestic");
        assertThat(router.route("buyer@163.com").name()).isEqualTo("domestic");
        // 子域也要命中：vip.qq.com、mail.163.com 都是常见真实地址
        assertThat(router.route("buyer@vip.qq.com").name()).isEqualTo("domestic");
        assertThat(router.route("buyer@mail.163.com").name()).isEqualTo("domestic");
    }

    @Test
    void unmatchedDomainFallsBackToTheCatchAllChannel() {
        MailChannelProperties props = new MailChannelProperties();
        props.setEnabled(true);
        props.getChannels().put("domestic", channel("smtp.domestic.test", "qq.com"));
        props.getChannels().put("overseas", channel("smtp.overseas.test")); // 空 domains = 兜底

        MailSenderRouter router = router(props);

        assertThat(router.route("someone@gmail.com").name()).isEqualTo("overseas");
        // 企业自建域、新顶级域都不在列表里，必须仍有出口
        assertThat(router.route("someone@mail.some-company.com.cn").name()).isEqualTo("overseas");
    }

    @Test
    void explicitDefaultChannelWinsOverAutoInference() {
        MailChannelProperties props = new MailChannelProperties();
        props.setEnabled(true);
        props.setDefaultChannel("domestic");
        props.getChannels().put("overseas", channel("smtp.overseas.test")); // 自动推断会选它
        props.getChannels().put("domestic", channel("smtp.domestic.test", "qq.com"));

        MailSenderRouter router = router(props);

        assertThat(router.route("someone@gmail.com").name()).isEqualTo("domestic");
    }

    @Test
    void incompleteChannelIsSkipped() {
        MailChannelProperties props = new MailChannelProperties();
        props.setEnabled(true);
        // 只填 host 不填口令 —— 典型的手工配置遗漏
        MailChannelProperties.Channel half = new MailChannelProperties.Channel();
        half.setHost("smtp.domestic.test");
        half.setPort(465);
        props.getChannels().put("domestic", half);
        props.getChannels().put("overseas", channel("smtp.overseas.test"));

        MailSenderRouter router = router(props);

        assertThat(router.routes()).extracting(MailSenderRouter.Route::name).containsExactly("overseas");
        assertThat(router.route("buyer@qq.com").name()).isEqualTo("overseas");
    }

    @Test
    void noChannelsFallsBackToSpringMail() {
        MailChannelProperties props = new MailChannelProperties();
        props.setEnabled(true);

        MailSenderRouter router = new MailSenderRouter(props, legacyProvider(new JavaMailSenderImpl()),
                "smtp.legacy.test", 465, "noreply@legacy.test", "secret");
        router.build();

        assertThat(router.isLegacyMode()).isTrue();
        assertThat(router.hasUsableChannel()).isTrue();
        assertThat(router.route("anyone@anywhere.com").name()).isEqualTo(MailSenderRouter.LEGACY_CHANNEL);
    }

    @Test
    void placeholderLegacyConfigIsNotUsable() {
        MailChannelProperties props = new MailChannelProperties();
        props.setEnabled(true);

        MailSenderRouter router = new MailSenderRouter(props, legacyProvider(new JavaMailSenderImpl()),
                "mail.example.com", 465, "noreply@example.com", "your_password");
        router.build();

        assertThat(router.hasUsableChannel()).isFalse();
        assertThat(router.route("anyone@anywhere.com")).isNull();
    }

    @Test
    void senderCarriesTimeouts() {
        MailChannelProperties props = new MailChannelProperties();
        props.setEnabled(true);
        MailChannelProperties.Channel c = channel("smtp.domestic.test", "qq.com");
        c.setConnectionTimeoutMs(1234);
        c.setReadTimeoutMs(2345);
        c.setWriteTimeoutMs(3456);
        props.getChannels().put("domestic", c);

        var sender = (JavaMailSenderImpl) router(props).routes().get(0).sender();

        // 没有超时的话，SMTP 无响应会无限挂起并占死 @Async 线程池
        assertThat(sender.getJavaMailProperties().getProperty("mail.smtp.connectiontimeout")).isEqualTo("1234");
        assertThat(sender.getJavaMailProperties().getProperty("mail.smtp.timeout")).isEqualTo("2345");
        assertThat(sender.getJavaMailProperties().getProperty("mail.smtp.writetimeout")).isEqualTo("3456");
    }

    @Test
    void starttlsDisablesImplicitSsl() {
        MailChannelProperties props = new MailChannelProperties();
        props.setEnabled(true);
        MailChannelProperties.Channel c = channel("smtp.overseas.test");
        c.setSsl(true);
        c.setStarttls(true); // 用户两个都开了，必须只生效一个
        props.getChannels().put("overseas", c);

        var sender = (JavaMailSenderImpl) router(props).routes().get(0).sender();

        assertThat(sender.getJavaMailProperties().getProperty("mail.smtp.starttls.enable")).isEqualTo("true");
        assertThat(sender.getJavaMailProperties().getProperty("mail.smtp.ssl.enable")).isEqualTo("false");
    }
}
