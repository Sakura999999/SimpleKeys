package com.simplecard.service;

import com.simplecard.config.ClientIpResolver;
import com.simplecard.context.RequestContext;
import com.simplecard.repository.OperationLogRepository;
import com.simplecard.entity.OperationLog;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.UUID;

@Service
public class SensitiveExportAudit {
    private final OperationLogRepository logs;private final ClientIpResolver ip;
    public SensitiveExportAudit(OperationLogRepository logs,ClientIpResolver ip){this.logs=logs;this.ip=ip;}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void prepared(UUID orderId,int count) {
        OperationLog log=new OperationLog();UUID user=RequestContext.getUserId();
        // Legacy audit table requires a non-null UUID. Zero denotes a guest, never an actual account.
        log.setUserId(user==null?new UUID(0,0):user);log.setUsername(user==null?"GUEST":"AUTHENTICATED");
        log.setAction("CARD_KEYS_EXPORT_PREPARED");log.setTargetType("ORDER");log.setTargetId(orderId.toString());
        log.setDetail("{\"outcome\":\"PREPARED\",\"cardCount\":"+count+"}");log.setIpAddress(ip.resolveCurrent());
        logs.saveAndFlush(log); // No card content, session token, body or payment signature is recorded.
    }
}
