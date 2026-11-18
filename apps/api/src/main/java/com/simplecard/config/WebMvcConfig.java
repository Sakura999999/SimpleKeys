package com.simplecard.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Value("${upload.path:./uploads}")
    private String uploadPath;

    /** CORS 允许的来源列表，逗号分隔。默认允许 localhost 开发环境。 */
    @Value("${cors.allowed-origins:http://localhost:3000,http://localhost:3001}")
    private String corsAllowedOrigins;

    private final PageBoundsInterceptor pageBoundsInterceptor;

    public WebMvcConfig(PageBoundsInterceptor pageBoundsInterceptor) {
        this.pageBoundsInterceptor = pageBoundsInterceptor;
    }

    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);  // 连接超时 5 秒
        factory.setReadTimeout(15_000);    // 读取超时 15 秒
        return new RestTemplate(factory);
    }

    /**
     * 分页参数闸门。放在拦截器里而不是各 controller，新接口自动被覆盖。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(pageBoundsInterceptor).addPathPatterns("/**");
    }

    /**
     * 允许的自定义请求头必须与实际发送的一致，否则跨域部署时预检直接失败。
     *
     * <p>此前只放行 {@code X-Session-Token}，而前端实际还会发
     * {@code X-Device-Id}、{@code X-Turnstile-Token}、{@code X-Order-Lookup-Token}。
     * 开发环境经 Next 的 rewrites 同源转发看不出来，一旦前后端分域（api.example.com）
     * 就会表现为"所有请求 403/CORS 报错"。
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = Arrays.stream(corsAllowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);

        if (origins.length == 0 || (origins.length == 1 && "*".equals(origins[0]))) {
            // 通配符模式：不允许 credentials
            registry.addMapping("/**")
                    .allowedOriginPatterns("*")
                    .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                    .allowedHeaders(ALLOWED_HEADERS)
                    .exposedHeaders("X-Session-Token")
                    .allowCredentials(false)
                    .maxAge(3600);
        } else {
            registry.addMapping("/**")
                    .allowedOrigins(origins)
                    .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                    .allowedHeaders(ALLOWED_HEADERS)
                    .exposedHeaders("X-Session-Token")
                    .allowCredentials(true)
                    .maxAge(3600);
        }
    }

    private static final String[] ALLOWED_HEADERS = {
            "Content-Type",
            "Authorization",
            "X-Session-Token",
            "X-Device-Id",
            "X-Turnstile-Token",
            "X-Order-Lookup-Token"
    };

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path dir = Paths.get(uploadPath);
        if (!dir.isAbsolute()) {
            dir = Paths.get(System.getProperty("user.dir")).resolve(uploadPath).normalize();
        }
        String location = "file:" + dir.toString().replace('\\', '/') + "/";
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(location);
    }
}
