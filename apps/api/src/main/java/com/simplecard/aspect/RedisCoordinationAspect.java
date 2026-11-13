package com.simplecard.aspect;
import com.simplecard.service.RedisOrderCoordinator;
import com.simplecard.repository.*;
import com.simplecard.entity.*;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import java.util.*;

@Aspect @Component @Order(-2000) @RequiredArgsConstructor
public class RedisCoordinationAspect {
    private final RedisOrderCoordinator coordination;
    private final OrderItemRepository items;
    private final CartItemRepository carts;
    private final UnmatchedTransactionRepository reviews;
    @Around("execution(* com.simplecard.service.impl.OrderServiceImpl.createDirectOrder(..)) || " +
        "execution(* com.simplecard.service.impl.OrderServiceImpl.createCartOrder(..)) || " +
        "execution(* com.simplecard.controller.AdminTxidReviewController.approveTxidReview(..)) || " +
        "execution(* com.simplecard.service.InventoryLifecycleService.reserve(..)) || " +
        "execution(* com.simplecard.service.InventoryLifecycleService.confirmPayment(..)) || " +
        "execution(* com.simplecard.service.InventoryLifecycleService.close(..)) || " +
        "execution(* com.simplecard.service.InventoryLifecycleService.recordRefund(..)) || " +
        "execution(* com.simplecard.service.InventoryLifecycleService.retryDelivery(..)) || " +
        "execution(* com.simplecard.service.InventoryLifecycleService.storePaymentDetails(..)) || " +
        "execution(* com.simplecard.service.InventoryLifecycleService.clearPaymentDetails(..))")
    public Object coordinate(ProceedingJoinPoint call) throws Throwable {
        if (!coordination.enabled()) return call.proceed();
        Object[] args=call.getArgs(); String name=call.getSignature().getName();
        List<String> keys=new ArrayList<>();
        if(name.startsWith("create")) {
            @SuppressWarnings("unchecked") Map<String,Object> req=(Map<String,Object>)args[0];
            UUID user=(UUID)args[1]; String session=(String)args[3];
            Object idem=req.get("idempotency_key");
            if(idem!=null) keys.add(coordination.requestKey(idem.toString()));
            if(name.equals("createCartOrder")) {
                keys.add(coordination.requestKey("cart:"+(user!=null?user:Objects.toString(session,"anonymous"))));
                List<CartItem> cart=user!=null?carts.findByUserId(user):(session!=null?carts.findBySessionToken(session):List.of());
                cart.forEach(i->keys.add(coordination.stockKey(i.getProductId(),i.getSpecId())));
            } else {
                try { keys.add(coordination.stockKey(UUID.fromString(Objects.toString(req.get("product_id"),"")), req.get("spec_id")==null?null:UUID.fromString(req.get("spec_id").toString()))); }
                catch(IllegalArgumentException e) { throw new com.simplecard.exception.BusinessException(com.simplecard.constant.ErrorCode.BAD_REQUEST,"商品或规格ID无效"); }
            }
        } else {
            UUID id=args[0] instanceof com.simplecard.entity.Order o?o.getId():(UUID)args[0];
            if(name.equals("approveTxidReview")) id=reviews.findById(id).map(UnmatchedTransaction::getOrderId).orElse(null);
            if(id!=null) {
                for(var item:items.findByOrderId(id)) keys.add(coordination.stockKey(item.getProductId(),item.getSpecId()));
                // Initial reservation is inside create-order's already-coordinated transaction.
                if(!(name.equals("reserve") && coordination.active())) keys.add(coordination.orderKey(id));
            }
        }
        return coordination.execute(keys,call::proceed);
    }
}
