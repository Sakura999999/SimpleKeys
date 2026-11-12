package com.simplecard.service.impl;

import com.simplecard.constant.ErrorCode;
import com.simplecard.entity.ProductCategory;
import com.simplecard.exception.BusinessException;
import com.simplecard.repository.ProductCategoryRepository;
import com.simplecard.repository.ProductRepository;
import com.simplecard.service.CategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
public class CategoryServiceImpl implements CategoryService {

    private final ProductCategoryRepository categoryRepository;
    private final ProductRepository productRepository;

    @Override
    @Transactional
    public void reorderCategories(List<UUID> ids) {
        List<ProductCategory> categories = categoryRepository.findByIsDeletedOrderBySortOrderAsc(0);
        Set<UUID> currentIds = new HashSet<>();
        categories.forEach(category -> currentIds.add(category.getId()));
        if (ids == null || ids.size() != currentIds.size() || !currentIds.equals(new HashSet<>(ids))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "分类列表已变化，请刷新后重新排序");
        }
        Map<UUID, ProductCategory> byId = new HashMap<>();
        categories.forEach(category -> byId.put(category.getId(), category));
        for (int index = 0; index < ids.size(); index++) {
            byId.get(ids.get(index)).setSortOrder(index);
        }
        categoryRepository.saveAll(categories);
    }

    @Override
    public List<?> listCategories() {
        return categoryRepository.findByIsDeletedOrderBySortOrderAsc(0).stream()
                .map(c -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("id", c.getId());
                    map.put("name", c.getName());
                    map.put("sort_order", c.getSortOrder());
                    return map;
                }).toList();
    }

    @Override
    @Transactional
    public void createCategory(Map<String, Object> req) {
        String name = (String) req.get("name");
        if (categoryRepository.existsByNameAndIsDeleted(name, 0)) {
            throw new BusinessException(ErrorCode.CATEGORY_NAME_EXISTS, "分类名称已存在");
        }
        ProductCategory category = new ProductCategory();
        category.setName(name);
        if (req.containsKey("sort_order")) category.setSortOrder(((Number) req.get("sort_order")).intValue());
        categoryRepository.save(category);
    }

    @Override
    @Transactional
    public void updateCategory(UUID id, Map<String, Object> req) {
        ProductCategory category = categoryRepository.findById(id)
                .filter(c -> c.getIsDeleted() == 0)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "分类不存在"));
        if (req.containsKey("name")) {
            String name = (String) req.get("name");
            if (categoryRepository.existsByNameAndIdNotAndIsDeleted(name, id, 0)) {
                throw new BusinessException(ErrorCode.CATEGORY_NAME_EXISTS, "分类名称已存在");
            }
            category.setName(name);
        }
        if (req.containsKey("sort_order")) category.setSortOrder(((Number) req.get("sort_order")).intValue());
        categoryRepository.save(category);
    }

    @Override
    @Transactional
    public void deleteCategory(UUID id) {
        ProductCategory category = categoryRepository.findById(id)
                .filter(c -> c.getIsDeleted() == 0)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "分类不存在"));
        if (productRepository.countByCategoryIdAndIsDeleted(id, 0) > 0) {
            throw new BusinessException(ErrorCode.CATEGORY_HAS_PRODUCTS, "该分类下存在商品，无法删除");
        }
        category.setIsDeleted(1);
        categoryRepository.save(category);
    }
}
