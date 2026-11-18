package com.simplecard.entity;

import com.simplecard.constant.RefundStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter @Setter @Entity
@Table(name = "refund_requests", uniqueConstraints = {
    @UniqueConstraint(name="uq_refund_order", columnNames="order_id"),
    @UniqueConstraint(name="uq_refund_channel_reference", columnNames={"payment_method","reference"})
})
public class RefundRequest extends BaseEntity {
    @Column(nullable=false) private UUID orderId;
    @Enumerated(EnumType.STRING) @Column(nullable=false) private RefundStatus status;
    @Column(nullable=false, precision=10, scale=2) private BigDecimal amount;
    @Column(nullable=false) private String currency;
    private String paymentMethod;
    @Column(precision=10, scale=2) private BigDecimal paymentAmount;
    private String paymentCurrency;
    @Column(nullable=false, length=500) private String reason;
    private UUID requestedBy;
    private UUID confirmedBy;
    @Column(length=250) private String reference;
    private LocalDateTime confirmedAt;
}
