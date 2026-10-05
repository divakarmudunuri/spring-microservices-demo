package com.smd.productservice.catalog;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CatalogQueryService {

    private final CategoryRepository categories;
    private final ProductRepository products;
    private final ProductAvailabilityRepository availability;

    public CatalogQueryService(CategoryRepository categories, ProductRepository products,
                               ProductAvailabilityRepository availability) {
        this.categories = categories;
        this.products = products;
        this.availability = availability;
    }

    public List<Category> listCategories() {
        return categories.findAllByOrderBySortOrderAscNameAsc();
    }

    /** Active products matching the filter (each part optional). */
    public Page<ProductView> listProducts(ProductFilter filter, Pageable pageable) {
        Page<Product> page = products.findAll(ProductSpecifications.matching(filter), pageable);
        Map<UUID, AvailabilityLevel> levels = levelsOf(page.getContent());
        return page.map(p -> view(p, levels));
    }

    /** By UUID, or by slug when the value isn't a UUID. */
    public ProductView getProduct(String idOrSlug) {
        Optional<Product> product = parseUuid(idOrSlug)
                .map(products::findByIdAndActiveTrue)
                .orElseGet(() -> products.findBySlugAndActiveTrue(idOrSlug));
        return product
                .map(p -> view(p, levelsOf(List.of(p))))
                .orElseThrow(() -> new ProductNotFoundException(idOrSlug));
    }

    /**
     * Batch lookup for other services (checkout prices, cart view, aggregator).
     * Unknown and inactive ids are simply missing from the result; the caller decides what that means.
     */
    public List<ProductView> getProducts(Collection<UUID> ids) {
        List<Product> found = products.findByIdInAndActiveTrue(ids);
        Map<UUID, AvailabilityLevel> levels = levelsOf(found);
        return found.stream().map(p -> view(p, levels)).toList();
    }

    private Map<UUID, AvailabilityLevel> levelsOf(Collection<Product> list) {
        return availability.findAllById(list.stream().map(Product::getId).toList()).stream()
                .collect(Collectors.toMap(ProductAvailability::getProductId, ProductAvailability::getLevel));
    }

    private static ProductView view(Product p, Map<UUID, AvailabilityLevel> levels) {
        // no availability row yet means no stock event has been seen for this product
        return new ProductView(p, levels.getOrDefault(p.getId(), AvailabilityLevel.OUT_OF_STOCK));
    }

    private static Optional<UUID> parseUuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
