package com.simplecard.service;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A database transaction barrier bridges Redis restarts with in-flight product publication.
 * Rebuild takes it once BEFORE scanning; public reads and the scan itself never hold it.
 */
@Component
public class ProductPublicationBarrier {
    private final JdbcTemplate jdbc;
    private final ReentrantLock embeddedTestLock = new ReentrantLock();
    public ProductPublicationBarrier(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void enter() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Publication barrier requires a transaction");
        try {
            String engine = jdbc.execute((ConnectionCallback<String>) c -> c.getMetaData().getDatabaseProductName());
            if ("PostgreSQL".equals(engine)) {
                jdbc.execute((ConnectionCallback<Void>) c -> {
                    // Dedicated namespace; transaction-scoped, automatically released on commit/rollback/crash.
                    try (var statement = c.prepareStatement("select pg_advisory_xact_lock(725614829031)")) {
                        statement.setQueryTimeout(3);
                        statement.execute();
                    }
                    return null;
                });
            } else if ("H2".equals(engine)) {
                // Embedded H2 is used only by single-process tests; production uses PostgreSQL.
                if (!embeddedTestLock.tryLock(3, TimeUnit.SECONDS)) throw ProductQueryProtection.busy();
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCompletion(int status) { embeddedTestLock.unlock(); }
                });
            } else throw new IllegalStateException("Unsupported publication barrier database: " + engine);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw ProductQueryProtection.busy();
        } catch (org.springframework.dao.DataAccessException e) { throw ProductQueryProtection.busy(); }
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public void awaitPreviousMutations(Runnable whileHeld) { enter(); whileHeld.run(); }
}
