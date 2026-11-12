package com.simplecard.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "currencies")
public class Currency extends BaseEntity {

    /** Administrator-maintained CNY per one unit; null means checkout is unavailable. */
    @Column(precision = 18, scale = 8)
    private java.math.BigDecimal rateToCny;

    @Column(nullable = false, unique = true, length = 10)
    private String code;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(nullable = false, length = 10)
    private String symbol;

    @Column(name = "is_enabled")
    private boolean enabled = true;

    private int sortOrder = 0;
}
