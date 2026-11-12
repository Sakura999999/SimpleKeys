package com.simplecard;

import com.simplecard.entity.ProductCategory;
import com.simplecard.exception.BusinessException;
import com.simplecard.repository.ProductCategoryRepository;
import com.simplecard.repository.ProductRepository;
import com.simplecard.service.impl.CategoryServiceImpl;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CategoryReorderTest {
    private ProductCategory category(int position) {
        var category = new ProductCategory(); category.setId(UUID.randomUUID()); category.setSortOrder(position); return category;
    }
    @Test void savesContiguousPositionsInRequestedOrder() {
        var first = category(7); var second = category(10);
        var repository = mock(ProductCategoryRepository.class);
        when(repository.findByIsDeletedOrderBySortOrderAsc(0)).thenReturn(List.of(first,second));
        new CategoryServiceImpl(repository,mock(ProductRepository.class)).reorderCategories(List.of(second.getId(),first.getId()));
        assertThat(first.getSortOrder()).isEqualTo(1);
        assertThat(second.getSortOrder()).isZero();
        verify(repository).saveAll(List.of(first,second));
    }
    @Test void rejectsDuplicateMissingAndUnknownIdsWithoutModifyingPositions() {
        var first = category(7); var second = category(10);
        var repository = mock(ProductCategoryRepository.class);
        when(repository.findByIsDeletedOrderBySortOrderAsc(0)).thenReturn(List.of(first,second));
        var service = new CategoryServiceImpl(repository,mock(ProductRepository.class));
        assertThatThrownBy(() -> service.reorderCategories(List.of(first.getId(),first.getId()))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.reorderCategories(List.of(first.getId()))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.reorderCategories(List.of(first.getId(),UUID.randomUUID()))).isInstanceOf(BusinessException.class);
        assertThat(first.getSortOrder()).isEqualTo(7);
        assertThat(second.getSortOrder()).isEqualTo(10);
        verify(repository, never()).saveAll(any());
    }
}
