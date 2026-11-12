package com.simplecard.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter @Setter @Entity @Table(name = "business_records")
public class BusinessRecord extends BaseEntity {
    @Column(nullable = false, length = 120) private String customerName;
    @Column(nullable = false, length = 255) private String purchaseContent;
    @Column(length = 120) private String bd;
    @Column(length = 120) private String channel;
    @Column(nullable = false) private int quantity;
    @Column(precision = 16, scale = 2) private BigDecimal unitPriceUsd;
    @Column(precision = 16, scale = 2) private BigDecimal unitPriceCny;
    @Column(precision = 16, scale = 2) private BigDecimal unitCostUsd;
    @Column(nullable = false) private boolean received;
    private LocalDateTime orderedAt;
    private LocalDateTime receivedAt;
    @Column(length = 2000) private String notes;
}
