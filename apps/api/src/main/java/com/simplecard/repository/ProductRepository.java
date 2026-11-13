package com.simplecard.repository;

import com.simplecard.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;
import java.util.List;

public interface ProductRepository extends JpaRepository<Product, UUID> {

    @Query("select new com.simplecard.repository.ProductPublicCore(p.id,p.basePrice,p.currency,p.categoryId," +
            "p.deliveryType,p.specEnabled,p.wholesaleEnabled,p.initialSales,p.createdAt,p.isDeleted,p.enabled) from Product p where p.id=:id")
    java.util.Optional<ProductPublicCore> publicCore(@Param("id") UUID id);

    @Query("select new com.simplecard.repository.ProductDisplayFields(p.title,p.titleEn,p.description,p.descriptionEn," +
            "p.detailMd,p.detailMdEn,p.coverUrl) from Product p where p.id=:id and p.isDeleted=0 and p.enabled=true")
    java.util.Optional<ProductDisplayFields> displayFields(@Param("id") UUID id);

    @Query("select p.id from Product p order by p.id")
    List<UUID> firstIds(Pageable pageable);

    @Query("select p.id from Product p where p.id > :after order by p.id")
    List<UUID> idsAfter(@Param("after") UUID after, Pageable pageable);

    // 首页商品列表 — 无搜索词
    @Query("SELECT p FROM Product p WHERE p.isDeleted = 0 AND p.enabled = true " +
            "AND (:categoryId IS NULL OR p.categoryId = :categoryId)")
    Page<Product> findPublicProducts(@Param("categoryId") UUID categoryId,
                                     Pageable pageable);

    // 首页商品列表 — 带搜索词（keyword 保证非 null）
    @Query("SELECT p FROM Product p WHERE p.isDeleted = 0 AND p.enabled = true " +
            "AND (:categoryId IS NULL OR p.categoryId = :categoryId) " +
            "AND (LOWER(p.title) LIKE :keywordPattern OR LOWER(p.titleEn) LIKE :keywordPattern)")
    Page<Product> findPublicProductsByKeyword(@Param("categoryId") UUID categoryId,
                                              @Param("keywordPattern") String keywordPattern,
                                              Pageable pageable);

    // 管理后台商品列表 — 无搜索词
    @Query("SELECT p FROM Product p WHERE p.isDeleted = 0 " +
            "AND (:categoryId IS NULL OR p.categoryId = :categoryId) " +
            "AND (:isEnabled IS NULL OR p.enabled = :isEnabled)")
    Page<Product> findAdminProducts(@Param("categoryId") UUID categoryId,
                                    @Param("isEnabled") Boolean isEnabled,
                                    Pageable pageable);

    // 管理后台商品列表 — 带搜索词（keyword 保证非 null）
    @Query("SELECT p FROM Product p WHERE p.isDeleted = 0 " +
            "AND (:categoryId IS NULL OR p.categoryId = :categoryId) " +
            "AND (LOWER(p.title) LIKE :keywordPattern OR LOWER(p.titleEn) LIKE :keywordPattern) " +
            "AND (:isEnabled IS NULL OR p.enabled = :isEnabled)")
    Page<Product> findAdminProductsByKeyword(@Param("categoryId") UUID categoryId,
                                             @Param("keywordPattern") String keywordPattern,
                                             @Param("isEnabled") Boolean isEnabled,
                                             Pageable pageable);

    long countByCategoryIdAndIsDeleted(UUID categoryId, int isDeleted);

    // 一次性迁移：将已有规格的商品自动设置 spec_enabled=true
    @Modifying
    @Query("UPDATE Product p SET p.specEnabled = true WHERE p.specEnabled = false " +
            "AND p.isDeleted = 0 AND EXISTS (" +
            "SELECT 1 FROM ProductSpec s WHERE s.productId = p.id AND s.isDeleted = 0)")
    int migrateSpecEnabled();
}
