package com.smd.productservice.catalog;

import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

/** Builds the WHERE clause of the product list from a {@link ProductFilter}. */
final class ProductSpecifications {

    private ProductSpecifications() {
    }

    static Specification<Product> matching(ProductFilter filter) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(cb.isTrue(root.get("active")));
            if (StringUtils.hasText(filter.category())) {
                where.add(cb.equal(root.get("category").get("slug"), filter.category()));
            }
            if (filter.featured() != null) {
                where.add(cb.equal(root.get("featured"), filter.featured()));
            }
            if (StringUtils.hasText(filter.q())) {
                // simple case-insensitive "contains"; full-text search (OpenSearch) is a later option
                String pattern = "%" + escapeLike(filter.q().trim().toLowerCase()) + "%";
                where.add(cb.or(cb.like(cb.lower(root.get("name")), pattern, '\\'),
                        cb.like(cb.lower(root.get("description")), pattern, '\\')));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }

    /** {@code %} and {@code _} in the search text are literal characters, not wildcards. */
    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
