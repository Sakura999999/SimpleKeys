package com.simplecard.service.impl;

import com.simplecard.common.PageResult;
import com.simplecard.constant.CardKeyStatus;
import com.simplecard.constant.ErrorCode;
import com.simplecard.entity.Product;
import com.simplecard.entity.ProductSpec;
import com.simplecard.entity.WholesaleRule;
import com.simplecard.exception.BusinessException;
import com.simplecard.repository.CardKeyRepository;
import com.simplecard.repository.OrderItemRepository;
import com.simplecard.repository.ProductRepository;
import com.simplecard.repository.ProductSpecRepository;
import com.simplecard.repository.WholesaleRuleRepository;
import com.simplecard.service.ProductService;
import com.simplecard.service.ProductQueryProtection;
import com.simplecard.service.ProductDisplayCache;
import com.simplecard.repository.ProductPublicCore;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepository;
    private final ProductSpecRepository productSpecRepository;
    private final WholesaleRuleRepository wholesaleRuleRepository;
    private final CardKeyRepository cardKeyRepository;
    private final OrderItemRepository orderItemRepository;
    private final ProductQueryProtection queryProtection;
    private final ProductDisplayCache displayCache;

    @Override
    public PageResult<?> listPublicProducts(UUID categoryId, String keyword, int page, int pageSize) {
        var pageable = PageRequest.of(page - 1, pageSize,
                Sort.by("sortOrder").ascending().and(Sort.by("createdAt").descending()));
        Page<Product> productPage;
        if (keyword != null && !keyword.isBlank()) {
            productPage = productRepository.findPublicProductsByKeyword(categoryId, "%" + keyword.toLowerCase() + "%", pageable);
        } else {
            productPage = productRepository.findPublicProducts(categoryId, pageable);
        }
        var list = productPage.getContent().stream().map(this::toProductCard).toList();
        return PageResult.of(productPage, list);
    }

    @Override
    public Map<String, Object> getProductDetail(UUID id) {
        String context = displayCache.context(id);
        return queryProtection.load(id, () -> productRepository.publicCore(id).map(ProductPublicCore::asProduct),
                product -> publicDetail(product, context));
    }

    private Map<String, Object> publicDetail(Product product, String context) {
        List<ProductSpec> specs = product.isSpecEnabled()
                ? productSpecRepository.findByProductIdAndIsDeletedOrderBySortOrderAsc(product.getId(), 0) : List.of();
        var counts = stockCounts(product.getId());
        Map<String,Object> detail = cardBase(product, specs, counts, true);
        detail.putAll(displayCache.get(product.getId(), context, () -> productRepository.displayFields(product.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "商品不存在或已下架")).asMap()));
        detail.put("specs", specs.stream().filter(ProductSpec::isVisible).map(spec -> toSpecMap(spec, counts)).toList());
        detail.put("spec_enabled", product.isSpecEnabled());
        detail.put("wholesale_enabled", product.isWholesaleEnabled());
        detail.put("wholesale_rules", wholesaleMaps(product.getId()));
        return detail;
    }

    @Override
    public Map<String, Object> getAdminProductDetail(UUID id) {
        Product product = productRepository.findById(id)
                .filter(p -> p.getIsDeleted() == 0)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "商品不存在"));
        Map<String, Object> detail = toProductDetail(product);
        detail.put("is_enabled", product.isEnabled());
        detail.put("sort_order", product.getSortOrder());
        detail.put("low_stock_threshold", product.getLowStockThreshold());
        detail.put("created_at", product.getCreatedAt());
        detail.put("updated_at", product.getUpdatedAt());
        return detail;
    }

    @Override
    public PageResult<?> listAdminProducts(UUID categoryId, String keyword, Boolean isEnabled, int page, int pageSize) {
        var pageable = PageRequest.of(page - 1, pageSize, Sort.by("sortOrder").ascending());
        Page<Product> productPage;
        if (keyword != null && !keyword.isBlank()) {
            productPage = productRepository.findAdminProductsByKeyword(categoryId, "%" + keyword.toLowerCase() + "%", isEnabled, pageable);
        } else {
            productPage = productRepository.findAdminProducts(categoryId, isEnabled, pageable);
        }
        var list = productPage.getContent().stream().map(p -> {
            Map<String, Object> detail = toProductDetail(p);
            detail.put("is_enabled", p.isEnabled());
            detail.put("sort_order", p.getSortOrder());
            detail.put("low_stock_threshold", p.getLowStockThreshold());
            detail.put("created_at", p.getCreatedAt());
            detail.put("updated_at", p.getUpdatedAt());
            return detail;
        }).toList();
        return PageResult.of(productPage, list);
    }

    @Override
    @Transactional
    public Map<String, Object> createProduct(Map<String, Object> req) {
        queryProtection.beforeMutation();
        Product product = new Product();
        product.setTitle((String) req.get("title"));
        product.setTitleEn((String) req.get("title_en"));
        product.setDescriptionEn((String) req.get("description_en"));
        product.setDetailMdEn((String) req.get("detail_md_en"));
        product.setDescription((String) req.get("description"));
        product.setDetailMd((String) req.get("detail_md"));
        product.setCoverUrl((String) req.get("cover_url"));
        product.setBasePrice(new BigDecimal(req.get("base_price").toString()));
        if (req.containsKey("currency")) product.setCurrency((String) req.get("currency"));
        if (req.containsKey("delivery_type")) product.setDeliveryType((String) req.get("delivery_type"));
        product.setCategoryId(UUID.fromString((String) req.get("category_id")));
        if (req.containsKey("low_stock_threshold")) product.setLowStockThreshold(((Number) req.get("low_stock_threshold")).intValue());
        if (req.containsKey("wholesale_enabled")) product.setWholesaleEnabled((boolean) req.get("wholesale_enabled"));
        if (req.containsKey("spec_enabled")) product.setSpecEnabled((boolean) req.get("spec_enabled"));
        if (req.containsKey("is_enabled")) product.setEnabled((boolean) req.get("is_enabled"));
        if (req.containsKey("initial_sales")) product.setInitialSales(((Number) req.get("initial_sales")).intValue());
        if (req.containsKey("sort_order")) product.setSortOrder(((Number) req.get("sort_order")).intValue());
        productRepository.save(product);
        queryProtection.mutation(product.getId());
        return toProductDetail(product);
    }

    @Override
    @Transactional
    public void updateProduct(UUID id, Map<String, Object> req) {
        queryProtection.beforeMutation();
        queryProtection.mutation(id);
        Product product = productRepository.findById(id)
                .filter(p -> p.getIsDeleted() == 0)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "商品不存在"));
        if (req.containsKey("title")) product.setTitle((String) req.get("title"));
        if (req.containsKey("title_en")) product.setTitleEn((String) req.get("title_en"));
        if (req.containsKey("description_en")) product.setDescriptionEn((String) req.get("description_en"));
        if (req.containsKey("detail_md_en")) product.setDetailMdEn((String) req.get("detail_md_en"));
        if (req.containsKey("description")) product.setDescription((String) req.get("description"));
        if (req.containsKey("detail_md")) product.setDetailMd((String) req.get("detail_md"));
        if (req.containsKey("cover_url")) product.setCoverUrl((String) req.get("cover_url"));
        if (req.containsKey("base_price")) product.setBasePrice(new BigDecimal(req.get("base_price").toString()));
        if (req.containsKey("currency")) product.setCurrency((String) req.get("currency"));
        if (req.containsKey("delivery_type")) product.setDeliveryType((String) req.get("delivery_type"));
        if (req.containsKey("category_id")) product.setCategoryId(UUID.fromString((String) req.get("category_id")));
        if (req.containsKey("low_stock_threshold")) product.setLowStockThreshold(((Number) req.get("low_stock_threshold")).intValue());
        if (req.containsKey("wholesale_enabled")) product.setWholesaleEnabled((boolean) req.get("wholesale_enabled"));
        if (req.containsKey("spec_enabled")) product.setSpecEnabled((boolean) req.get("spec_enabled"));
        if (req.containsKey("is_enabled")) product.setEnabled((boolean) req.get("is_enabled"));
        if (req.containsKey("initial_sales")) product.setInitialSales(((Number) req.get("initial_sales")).intValue());
        if (req.containsKey("sort_order")) product.setSortOrder(((Number) req.get("sort_order")).intValue());
        productRepository.save(product);
    }

    @Override
    @Transactional
    public void deleteProduct(UUID id) {
        queryProtection.beforeMutation();
        queryProtection.mutation(id);
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "商品不存在"));
        product.setIsDeleted(1);
        productRepository.save(product);
    }

    @Override
    public Object listSpecs(UUID productId) {
        var counts = stockCounts(productId);
        return productSpecRepository.findByProductIdAndIsDeletedOrderBySortOrderAsc(productId, 0).stream()
                .map(spec -> toSpecMap(spec, counts)).toList();
    }

    @Override
    @Transactional
    public void createSpec(UUID productId, Map<String, Object> req) {
        productRepository.findById(productId)
                .filter(p -> p.getIsDeleted() == 0)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "商品不存在"));

        String name = (String) req.get("name");
        if (productSpecRepository.existsByProductIdAndNameAndIsDeleted(productId, name, 0)) {
            throw new BusinessException(ErrorCode.SPEC_NAME_DUPLICATE, "同一商品下规格名称不能重复");
        }

        ProductSpec spec = new ProductSpec();
        spec.setProductId(productId);
        spec.setName(name);
        spec.setPrice(new BigDecimal(req.get("price").toString()));
        if (req.containsKey("is_visible")) spec.setVisible((boolean) req.get("is_visible"));
        if (req.containsKey("sort_order")) spec.setSortOrder(((Number) req.get("sort_order")).intValue());
        productSpecRepository.save(spec);
    }

    @Override
    @Transactional
    public void updateSpec(UUID productId, UUID specId, Map<String, Object> req) {
        ProductSpec spec = productSpecRepository.findById(specId)
                .filter(s -> s.getProductId().equals(productId) && s.getIsDeleted() == 0)
                .orElseThrow(() -> new BusinessException(ErrorCode.SPEC_NOT_FOUND, "规格不存在"));

        if (req.containsKey("name")) {
            String newName = (String) req.get("name");
            if (!newName.equals(spec.getName())
                    && productSpecRepository.existsByProductIdAndNameAndIsDeleted(productId, newName, 0)) {
                throw new BusinessException(ErrorCode.SPEC_NAME_DUPLICATE, "同一商品下规格名称不能重复");
            }
            spec.setName(newName);
        }
        if (req.containsKey("price")) spec.setPrice(new BigDecimal(req.get("price").toString()));
        if (req.containsKey("is_visible")) spec.setVisible((boolean) req.get("is_visible"));
        if (req.containsKey("sort_order")) spec.setSortOrder(((Number) req.get("sort_order")).intValue());
        productSpecRepository.save(spec);
    }

    @Override
    @Transactional
    public void deleteSpec(UUID productId, UUID specId) {
        ProductSpec spec = productSpecRepository.findById(specId)
                .filter(s -> s.getProductId().equals(productId))
                .orElseThrow(() -> new BusinessException(ErrorCode.SPEC_NOT_FOUND, "规格不存在"));

        // 自动作废该规格下的可用卡密（AVAILABLE → INVALID），避免留下永远无法售出的幽灵库存
        cardKeyRepository.updateStatusByProductIdAndSpecId(
                productId, specId, CardKeyStatus.AVAILABLE, CardKeyStatus.INVALID);

        spec.setIsDeleted(1);
        productSpecRepository.save(spec);
    }

    @Override
    public Object listWholesaleRules(UUID productId) {
        return wholesaleRuleRepository.findByProductIdOrderByMinQuantityAsc(productId).stream()
                .map(r -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("min_quantity", r.getMinQuantity());
                    m.put("unit_price", r.getUnitPrice());
                    return m;
                }).toList();
    }

    @Override
    @Transactional
    @SuppressWarnings("unchecked")
    public void setWholesaleRules(UUID productId, Map<String, Object> req) {
        UUID specId = req.containsKey("spec_id") && req.get("spec_id") != null
                ? UUID.fromString((String) req.get("spec_id")) : null;
        if (specId != null) {
            wholesaleRuleRepository.deleteByProductIdAndSpecId(productId, specId);
        } else {
            wholesaleRuleRepository.deleteByProductIdAndSpecIdIsNull(productId);
        }
        List<Map<String, Object>> rules = (List<Map<String, Object>>) req.get("rules");
        for (Map<String, Object> r : rules) {
            WholesaleRule rule = new WholesaleRule();
            rule.setProductId(productId);
            rule.setSpecId(specId);
            rule.setMinQuantity(((Number) r.get("min_quantity")).intValue());
            rule.setUnitPrice(new BigDecimal(r.get("unit_price").toString()));
            wholesaleRuleRepository.save(rule);
        }
    }

    private Map<UUID, EnumMap<CardKeyStatus,Long>> stockCounts(UUID productId) {
        Map<UUID, EnumMap<CardKeyStatus,Long>> result = new HashMap<>();
        for (Object[] row : cardKeyRepository.countsBySpecAndStatus(productId))
            result.computeIfAbsent((UUID) row[0], ignored -> new EnumMap<>(CardKeyStatus.class))
                    .put((CardKeyStatus) row[1], ((Number) row[2]).longValue());
        return result;
    }

    private long available(Map<UUID, EnumMap<CardKeyStatus,Long>> counts, UUID specId) {
        var byStatus = counts.get(specId);
        return byStatus == null ? 0 : byStatus.getOrDefault(CardKeyStatus.AVAILABLE, 0L);
    }

    private Map<String,Object> toProductCard(Product p) {
        var specs = productSpecRepository.findByProductIdAndIsDeletedOrderBySortOrderAsc(p.getId(), 0);
        return cardBase(p, specs, stockCounts(p.getId()), true);
    }

    private Map<String,Object> cardBase(Product p, List<ProductSpec> specs,
            Map<UUID, EnumMap<CardKeyStatus,Long>> counts, boolean publicOnly) {
        Map<String,Object> map = new LinkedHashMap<>();
        map.put("id", p.getId()); map.put("title", p.getTitle()); map.put("title_en", p.getTitleEn());
        map.put("description", p.getDescription()); map.put("description_en", p.getDescriptionEn());
        map.put("cover_url", p.getCoverUrl()); map.put("base_price", p.getBasePrice());
        map.put("currency", p.getCurrency()); map.put("category_id", p.getCategoryId());
        boolean hasSpecs = p.isSpecEnabled() && !specs.isEmpty();
        long stock = hasSpecs ? specs.stream().filter(spec -> !publicOnly || spec.isVisible())
                .mapToLong(spec -> available(counts, spec.getId())).sum() : available(counts, null);
        map.put("stock_available", stock); map.put("has_specs", hasSpecs);
        map.put("delivery_type", p.getDeliveryType());
        map.put("sales_count", orderItemRepository.sumQuantityByProductId(p.getId()));
        map.put("initial_sales", p.getInitialSales()); map.put("created_at", p.getCreatedAt());
        return map;
    }

    private List<Map<String,Object>> wholesaleMaps(UUID id) {
        return wholesaleRuleRepository.findByProductIdOrderByMinQuantityAsc(id).stream().map(r -> {
            Map<String,Object> m = new HashMap<>(); m.put("min_quantity", r.getMinQuantity());
            m.put("unit_price", r.getUnitPrice()); return m;
        }).toList();
    }

    private Map<String,Object> toProductDetail(Product p) {
        var specs = productSpecRepository.findByProductIdAndIsDeletedOrderBySortOrderAsc(p.getId(), 0);
        var counts = stockCounts(p.getId());
        Map<String,Object> map = cardBase(p, specs, counts, false);
        map.put("detail_md", p.getDetailMd()); map.put("detail_md_en", p.getDetailMdEn());
        map.put("specs", specs.stream().map(spec -> toSpecMap(spec, counts)).toList());
        map.put("spec_enabled", p.isSpecEnabled()); map.put("wholesale_enabled", p.isWholesaleEnabled());
        map.put("wholesale_rules", wholesaleMaps(p.getId())); return map;
    }

    private Map<String,Object> toSpecMap(ProductSpec spec, Map<UUID, EnumMap<CardKeyStatus,Long>> counts) {
        Map<String,Object> map = new LinkedHashMap<>();
        map.put("id", spec.getId()); map.put("name", spec.getName()); map.put("price", spec.getPrice());
        map.put("stock_available", available(counts, spec.getId()));
        var byStatus = counts.get(spec.getId());
        long valid = byStatus == null ? 0 : byStatus.entrySet().stream()
                .filter(e -> e.getKey() != CardKeyStatus.INVALID).mapToLong(Map.Entry::getValue).sum();
        map.put("card_key_count", valid); return map;
    }
}
