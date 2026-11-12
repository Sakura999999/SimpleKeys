package com.simplecard.controller;

import com.simplecard.config.ClientIpResolver;
import com.simplecard.service.VisitService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/visit")
@RequiredArgsConstructor
public class VisitController {

    private final VisitService visitService;
    private final ClientIpResolver clientIpResolver;

    @PostMapping("/track")
    public ResponseEntity<Void> track(HttpServletRequest request) {
        // 原实现无条件读取 X-Forwarded-For 首项，任何客户端都能自行指定 IP 来伪造访问统计。
        // 统一走 ClientIpResolver：仅在直连来源属于受信代理时才采信代理头。
        String ip = clientIpResolver.resolve(request);
        visitService.track(ip);
        return ResponseEntity.noContent().build();
    }
}
