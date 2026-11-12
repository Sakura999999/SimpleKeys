package com.simplecard;

import com.simplecard.service.ProductService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.mail.host=localhost")
@Transactional
class ProductTranslationIntegrationTest {
    @Autowired ProductService service;
    @Autowired EntityManager entityManager;

    @Test void translationsPersistAndCanBeClearedWithoutChangingOriginalContent() {
        var created = service.createProduct(Map.of(
                "title", "中文商品", "description", "中文简介", "detail_md", "中文详情",
                "title_en", "English product", "description_en", "English summary",
                "detail_md_en", "## English details", "base_price", 12,
                "category_id", UUID.randomUUID().toString()));
        UUID id = (UUID) created.get("id");
        entityManager.flush();
        entityManager.clear();
        var detail = service.getProductDetail(id);
        assertThat(detail).containsEntry("title_en", "English product")
                .containsEntry("description_en", "English summary")
                .containsEntry("detail_md_en", "## English details");
        assertThat(service.listPublicProducts(null, "english product", 1, 20).getList()).hasSize(1);
        service.updateProduct(id, Map.of("title_en", "", "description_en", "", "detail_md_en", ""));
        entityManager.flush();
        entityManager.clear();
        assertThat(service.getAdminProductDetail(id)).containsEntry("title_en", "")
                .containsEntry("description_en", "").containsEntry("detail_md_en", "")
                .containsEntry("title", "中文商品").containsEntry("description", "中文简介")
                .containsEntry("detail_md", "中文详情");
    }

    @Test void existingProductsCanBeUpdatedWithoutSupplyingTranslations() {
        var created = service.createProduct(Map.of("title", "原有商品", "base_price", 12,
                "category_id", UUID.randomUUID().toString(), "title_en", "Existing translation"));
        UUID id = (UUID) created.get("id");
        service.updateProduct(id, Map.of("title", "更新中文"));
        entityManager.flush();
        entityManager.clear();
        assertThat(service.getProductDetail(id)).containsEntry("title", "更新中文")
                .containsEntry("title_en", "Existing translation");
    }
}
