package com.simplecard.aspect;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Retry outside the transaction interceptor, so each attempt gets a fresh transaction. */
@Aspect
@Component
@Order(-1000)
public class InventoryLockRetryAspect {
    @Around("execution(public * com.simplecard.service.InventoryLifecycleService.*(..)) || " +
            "execution(* com.simplecard.service.impl.OrderServiceImpl.createDirectOrder(..)) || " +
            "execution(* com.simplecard.service.impl.OrderServiceImpl.createCartOrder(..))")
    public Object retry(ProceedingJoinPoint call) throws Throwable {
        // A caller-owned transaction must be rolled back by that caller, never retried in place.
        if (TransactionSynchronizationManager.isActualTransactionActive()) return call.proceed();
        for (int attempt = 0; ; attempt++) {
            try { return call.proceed(); }
            catch (PessimisticLockingFailureException | QueryTimeoutException conflict) {
                if (attempt == 2) throw conflict;
                try { Thread.sleep(25L * (attempt + 1)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw conflict; }
            }
        }
    }
}
