package com.simplecard.service;
import com.simplecard.entity.VerifiedPaymentEvent;
import com.simplecard.repository.*;
import com.simplecard.exception.BusinessException;
import com.simplecard.constant.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import java.time.LocalDateTime;
import java.util.*;

/** Stores only VERIFIED payment facts. Redis availability never controls durable receipt. */
@Slf4j @Service
public class PaymentInboxService {
    private final JdbcTemplate jdbc;
    private final VerifiedPaymentEventRepository events;
    private final OrderItemRepository items;
    private final InventoryLifecycleService inventory;
    private final RedisOrderCoordinator coordinator;
    private final TransactionTemplate transaction;
    private final boolean workerEnabled;
    public PaymentInboxService(JdbcTemplate jdbc,VerifiedPaymentEventRepository events,OrderItemRepository items,
            InventoryLifecycleService inventory,RedisOrderCoordinator coordinator,PlatformTransactionManager manager,
            @Value("${payment-inbox.worker-enabled:true}") boolean workerEnabled) {
        this.jdbc=jdbc;this.events=events;this.items=items;this.inventory=inventory;this.coordinator=coordinator;
        this.transaction=new TransactionTemplate(manager);this.transaction.setTimeout(15);this.workerEnabled=workerEnabled;
    }
    public void accept(String key,String channel,UUID orderId,String txid) {
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Payment receipt needs its own committed transaction");
        if(key==null || key.isBlank() || key.length()>255 || channel==null || orderId==null)
            throw new BusinessException(ErrorCode.BAD_REQUEST,"付款事件标识无效");
        if(jdbc.queryForObject("select count(*) from orders where id=?",Integer.class,orderId)==0)
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND,"订单不存在");
        LocalDateTime now=LocalDateTime.now();
        // PostgreSQL ON CONFLICT makes duplicate receipt atomic across instances.
        jdbc.update("insert into verified_payment_events(id,event_key,channel_code,order_id,tx_id,processed,retry_count,next_attempt_at,created_at,updated_at) values(?,?,?,?,?,false,0,?,?,?) on conflict(event_key) do nothing",
                UUID.randomUUID(),key,channel,orderId,txid,now,now,now);
        VerifiedPaymentEvent event=events.findByEventKey(key).orElseThrow();
        if(!orderId.equals(event.getOrderId()) || !Objects.equals(txid,event.getTxId()) || !channel.equals(event.getChannelCode()))
            throw new BusinessException(ErrorCode.BAD_REQUEST,"付款事件与原记录不一致");
        process(event.getId());
    }
    public boolean process(UUID id) {
        VerifiedPaymentEvent snapshot=events.findById(id).orElseThrow();
        if(snapshot.isProcessed()) return true;
        List<String> keys=new ArrayList<>();keys.add(coordinator.orderKey(snapshot.getOrderId()));
        items.findByOrderId(snapshot.getOrderId()).forEach(i->keys.add(coordinator.stockKey(i.getProductId(),i.getSpecId())));
        try {
            return coordinator.execute(keys,()-> transaction.execute(status -> {
                VerifiedPaymentEvent event=events.lockEvent(id).orElseThrow();
                if(event.isProcessed()) return true;
                inventory.confirmPayment(event.getOrderId(),event.getTxId());
                event.setProcessed(true);event.setLastError(null);events.save(event);
                return true;
            }));
        } catch(Throwable failure) {
            if(failure instanceof Error fatal) throw fatal;
            int seconds=Math.min(300,5*(1<<Math.min(6,snapshot.getRetryCount())));
            jdbc.update("update verified_payment_events set retry_count=retry_count+1,next_attempt_at=?,last_error=?,updated_at=? where id=? and processed=false",
                    LocalDateTime.now().plusSeconds(seconds),failure.getClass().getSimpleName(),LocalDateTime.now(),id);
            if(snapshot.getRetryCount()>=9) log.error("Verified payment requires operator attention: event={}, attempts={}",id,snapshot.getRetryCount()+1);
            log.warn("Verified payment deferred: event={}, attempt={}, cause={}",id,snapshot.getRetryCount()+1,failure.getClass().getSimpleName());
            return false;
        }
    }
    @Scheduled(fixedDelayString="${payment-inbox.poll-ms:5000}")
    public void retryPending() {
        if(!workerEnabled) return;
        for(var event:events.findByProcessedFalseAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(LocalDateTime.now(),PageRequest.of(0,10))) {
            try { process(event.getId()); } catch(RuntimeException e) { log.error("Payment retry requires attention: event={}, cause={}",event.getId(),e.getClass().getSimpleName()); }
        }
    }
}
