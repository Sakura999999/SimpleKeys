package com.simplecard.entity;

import com.simplecard.constant.CardKeyStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "card_keys", indexes = {@Index(name="idx_keys_pool_status",columnList="product_id,spec_id,status,created_at"), @Index(name="idx_keys_order",columnList="order_id")})
public class CardKey extends BaseEntity {

    @Column(nullable = false)
    private UUID productId;

    private UUID specId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CardKeyStatus status = CardKeyStatus.AVAILABLE;

    private UUID orderId;

    private UUID orderItemId;

    private UUID importBatchId;

    private LocalDateTime soldAt;
}
