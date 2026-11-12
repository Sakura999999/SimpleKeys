package com.simplecard.repository;

import com.simplecard.entity.Currency;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CurrencyRepository extends JpaRepository<Currency, UUID> {
    java.util.Optional<Currency> findByCodeAndEnabled(String code, boolean enabled);

    List<Currency> findByEnabledOrderBySortOrderAsc(boolean enabled);
}
