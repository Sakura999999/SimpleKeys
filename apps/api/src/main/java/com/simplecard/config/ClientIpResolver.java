package com.simplecard.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端真实 IP 解析 — 全站唯一实现。
 *
 * <p>生产环境经 Nginx 反代后 {@code getRemoteAddr()} 返回代理地址（Docker 环境为网桥 IP
 * 如 172.18.0.1），必须从代理头还原真实来源 IP。
 *
 * <p><b>代理头只有来自受信代理的请求才可信。</b>任何客户端都能自行发送
 * {@code X-Forwarded-For: 1.2.3.4}，因此在未确认直连来源属于受信代理前读取代理头，
 * 等于让调用方自由指定自己的 IP —— 限流、风控、封禁、审计日志会全部失效。
 *
 * <p>取值优先级：
 * <ol>
 *   <li>{@code X-Real-IP} — Nginx 以 {@code $remote_addr} 覆写，客户端无法伪造</li>
 *   <li>{@code X-Forwarded-For} 中<b>最右侧</b>的非受信 IP —— 即最后一个受信代理追加的那项。
 *       首项可被客户端注入，不可用于安全决策</li>
 *   <li>直连地址</li>
 * </ol>
 *
 * <p>受信代理列表读取 {@code rate-limit.trusted-proxies}。<b>Docker 部署必须把网桥网关 IP
 * 加入该列表</b>，否则所有请求的来源 IP 都会解析成同一个代理地址。
 */
@Component
public class ClientIpResolver {

    @Value("${rate-limit.trusted-proxies:127.0.0.1,::1,0:0:0:0:0:0:0:1}")
    private String trustedProxiesConfig;

    private volatile Set<String> trustedProxies;

    public Set<String> getTrustedProxies() {
        if (trustedProxies == null) {
            Set<String> set = ConcurrentHashMap.newKeySet();
            if (trustedProxiesConfig != null) {
                for (String ip : trustedProxiesConfig.split(",")) {
                    if(ip.isBlank())continue;
                    String trimmed = normalize(ip);
                    if(trimmed==null)throw new IllegalArgumentException("Trusted proxies require exact numeric IP addresses");
                    set.add(trimmed);
                }
            }
            trustedProxies = set;
        }
        return trustedProxies;
    }
    @jakarta.annotation.PostConstruct
    public void validateConfiguration() { getTrustedProxies(); }

    /** 解析指定请求的客户端真实 IP。 */
    public String resolve(HttpServletRequest request) {
        String remoteAddr = normalize(request.getRemoteAddr());
        if (remoteAddr==null) return "unknown";

        if (getTrustedProxies().contains(remoteAddr)) {
            String realIp = request.getHeader("X-Real-IP");
            if (normalize(realIp)!=null) {
                return normalize(realIp);
            }
            String xff = request.getHeader("X-Forwarded-For");
            if (StringUtils.hasText(xff)) {
                String[] parts = xff.split(",");
                if (parts.length>20) return remoteAddr;
                for (int i = parts.length - 1; i >= 0; i--) {
                    String ip = normalize(parts[i]);
                    if (ip==null) return remoteAddr;
                    if (!ip.isEmpty() && !getTrustedProxies().contains(ip)) {
                        return ip;
                    }
                }
                return normalize(parts[0]);
            }
        }

        return remoteAddr;
    }
    private static String normalize(String raw) {
        if(raw==null)return null;String value=raw.trim();
        if(value.isEmpty() || value.length()>45)return null;
        try {
            if(value.contains(":")) {
                if(!value.matches("[0-9a-fA-F:.]+"))return null;
                return java.net.InetAddress.getByName(value).getHostAddress(); // numeric only, never resolves hostnames
            }
            String[] pieces=value.split("\\.",-1);if(pieces.length!=4)return null;
            StringBuilder result=new StringBuilder();
            for(String piece:pieces) {
                if(!piece.matches("[0-9]{1,3}"))return null;int n=Integer.parseInt(piece);if(n>255)return null;
                if(!result.isEmpty())result.append('.');result.append(n);
            }
            return result.toString();
        } catch(Exception e) { return null; }
    }

    /**
     * 解析当前线程绑定请求的客户端真实 IP，供拿不到 HttpServletRequest 的切面使用。
     *
     * @return 无请求上下文时返回 "unknown"
     */
    public String resolveCurrent() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return "unknown";
        }
        return resolve(attrs.getRequest());
    }
}
