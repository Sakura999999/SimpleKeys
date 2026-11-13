package com.simplecard.repository;

import com.simplecard.entity.Product;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/** No large display text: every public detail request still reads visibility and pricing from SQL. */
public record ProductPublicCore(UUID id, BigDecimal basePrice, String currency, UUID categoryId,
        String deliveryType, boolean specEnabled, boolean wholesaleEnabled, int initialSales,
        LocalDateTime createdAt, int isDeleted, boolean enabled) {
    public Product asProduct() {
        Product p = new Product();
        p.setId(id); p.setBasePrice(basePrice); p.setCurrency(currency); p.setCategoryId(categoryId);
        p.setDeliveryType(deliveryType); p.setSpecEnabled(specEnabled); p.setWholesaleEnabled(wholesaleEnabled);
        p.setInitialSales(initialSales); p.setCreatedAt(createdAt); p.setIsDeleted(isDeleted); p.setEnabled(enabled);
        return p;
    }
}
