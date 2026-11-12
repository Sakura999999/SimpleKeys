package com.simplecard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplecard.controller.AdminBusinessRecordController;
import com.simplecard.entity.BusinessRecord;
import com.simplecard.repository.BusinessRecordRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.mail.host=localhost")
@Transactional
class BusinessRecordIntegrationTest {
    @Autowired AdminBusinessRecordController controller;
    @Autowired BusinessRecordRepository repository;
    @Autowired ObjectMapper mapper;

    @Test void parsesBusinessFormDateFormat() throws Exception {
        String json = "{\"customer_name\":\"test\",\"purchase_content\":\"item\",\"quantity\":1,\"ordered_at\":\"2026-11-12 18:57:00\",\"received_at\":\"2026-11-11 21:57:00\"}";
        var input = mapper.readValue(json, AdminBusinessRecordController.Input.class);
        assertThat(input.orderedAt()).isEqualTo(LocalDateTime.of(2026, 11, 12, 18, 57));
        assertThat(input.receivedAt()).isEqualTo(LocalDateTime.of(2026, 11, 11, 21, 57));
    }

    @Test void createsUpdatesAndFiltersRecord() throws Exception {
        String marker = "integration-" + java.util.UUID.randomUUID();
        var input = new AdminBusinessRecordController.Input(marker, "软件", "测试BD", "直客", 2,
                new BigDecimal("10.25"), new BigDecimal("72.00"), new BigDecimal("4.00"), false,
                LocalDateTime.of(2025, 8, 5, 12, 0), null, "备注");
        BusinessRecord created = controller.create(input).getData();
        repository.flush();
        assertThat(created.getId()).isNotNull();
        assertThat(mapper.writeValueAsString(created)).contains("\"customer_name\"", "\"unit_price_usd\"");
        Page<?> found = (Page<?>) controller.list(0, 20, marker, "测试BD", false, "2025-08").getData();
        assertThat(found.getTotalElements()).isEqualTo(1);
        Page<?> wrongMonth = (Page<?>) controller.list(0, 20, marker, null, null, "2025-09").getData();
        assertThat(wrongMonth.getTotalElements()).isZero();
        var updated = new AdminBusinessRecordController.Input(marker, "软件", "测试BD", "直客", 2,
                new BigDecimal("10.25"), new BigDecimal("72.00"), new BigDecimal("4.00"), true,
                input.orderedAt(), LocalDateTime.of(2025, 8, 6, 12, 0), "已到账");
        controller.update(created.getId(), updated);
        repository.flush();
        assertThat(((Page<?>) controller.list(0, 20, marker, null, true, null).getData()).getTotalElements()).isEqualTo(1);
    }
}


