package com.simplecard.entity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;
import java.util.UUID;
@Getter @Setter @Entity
@Table(name="verified_payment_events", indexes={@Index(name="idx_payment_events_due",columnList="processed,next_attempt_at"),@Index(name="idx_payment_events_order",columnList="order_id,processed")})
public class VerifiedPaymentEvent extends BaseEntity {
    @Column(nullable=false,unique=true) private String eventKey;
    @Column(nullable=false) private String channelCode;
    @Column(nullable=false) private UUID orderId;
    private String txId;
    @Column(nullable=false) private boolean processed;
    @Column(nullable=false) private int retryCount;
    @Column(nullable=false) private LocalDateTime nextAttemptAt;
    private String lastError;
}
