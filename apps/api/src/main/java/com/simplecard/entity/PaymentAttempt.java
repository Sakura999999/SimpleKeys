package com.simplecard.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Getter
@Setter
@Table(name = "payment_attempts", indexes = @Index(name = "idx_payment_attempt_due", columnList = "next_check_at"))
public class PaymentAttempt extends BaseEntity {
    public enum State { CREATING, UNKNOWN, READY, VERIFIED, REJECTED }
    public static final String PAID_UNVERIFIED = "PAID_UNVERIFIED";

    @Column(nullable = false, unique = true)
    private UUID orderId;
    @Column(nullable = false)
    private UUID channelId;
    @Column(nullable = false, length = 16)
    private String provider;
    @Column(nullable = false, length = 1024)
    private String gatewayUrl;
    private String merchantId;
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;
    @Column(nullable = false, length = 8)
    private String currency;
    private String gatewayTradeId;
    private String pendingTxId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private State state;
    @Column(nullable = false)
    private LocalDateTime expiresAt;
    private LocalDateTime nextCheckAt;
    private int checkFailures;
    private String lastCheckResult;
}
