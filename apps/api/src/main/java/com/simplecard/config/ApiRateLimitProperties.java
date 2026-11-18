package com.simplecard.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.*;

@Data
@Component
@ConfigurationProperties("api-rate-limit")
public class ApiRateLimitProperties {
    private boolean enabled=true;
    private boolean shadow=false;
    private String address="redis://127.0.0.1:6390";
    private String password="";
    private String prefix="simplecard:api-limits";
    private int maxPeers=5000;
    private double memoryStopRatio=0.75;
    private Map<String,Policy> policies=new LinkedHashMap<>();
    public ApiRateLimitProperties() {
        add("login",60000,300,5); add("register",3600000,200,10);
        add("create-order",1000,50,3); add("create-user",1000,50,3);
        add("quote",1000,100,10); add("cart",1000,100,10);
        add("catalog",1000,200,20); add("query",60000,600,30);
        add("order-edge",1000,100,20); add("misc",1000,1000,50);
        add("status",60000,6000,60); add("refresh",60000,600,10); add("cancel",60000,600,10);
        add("export",60000,100,5); add("repay-upstream",1000,20,2);
        add("repay-order",30000,600,1); add("txid-upstream",1000,5,2);
        add("txid-order",86400000,5000,3);
        // 访客订单找回：发送按 IP 与邮箱两个维度分别限，验证按 IP 与邮箱分别限。
        // 只按 IP 限不够——同一 IP 换邮箱即可把我们的 SMTP 当免费发信通道。
        add("email-code-send",3600000,200,8);        // 1 小时 / 全局 200 / 单 IP 8
        add("email-code-send-email",3600000,200,5);  // 1 小时 / 单邮箱 5
        add("email-code-verify",600000,300,12);      // 10 分钟 / 全局 300 / 单 IP 12
        add("email-code-verify-email",600000,60,10); // 10 分钟 / 单邮箱 10
    }
    private void add(String scope,long window,int global,int peer) { policies.put(scope,new Policy(window,global,peer)); }
    @Data
    public static class Policy {
        private long windowMs=1000;
        private int global=100;
        private int peer=10;
        private int concurrency=8;
        public Policy() {}
        public Policy(long window,int global,int peer) { this.windowMs=window;this.global=global;this.peer=peer; }
    }
}
