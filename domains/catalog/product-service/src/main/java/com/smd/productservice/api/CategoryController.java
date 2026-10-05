package com.smd.productservice.api;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/categories")
public class CategoryController {

    private final CatalogCache cache;

    public CategoryController(CatalogCache cache) {
        this.cache = cache;
    }

    @GetMapping
    public List<CategoryResponse> list() {
        return cache.categories();
    }
}
