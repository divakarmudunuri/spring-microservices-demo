package com.smd.productservice.api;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/** A stable JSON shape for paged results (Spring's {@code PageImpl} isn't meant to be serialized). */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    static <S, T> PageResponse<T> from(Page<S> page, Function<S, T> mapper) {
        return new PageResponse<>(page.getContent().stream().map(mapper).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
