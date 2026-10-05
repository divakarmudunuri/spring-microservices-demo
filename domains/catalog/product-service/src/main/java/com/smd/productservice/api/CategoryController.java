package com.smd.productservice.api;

import com.smd.productservice.catalog.CatalogQueryService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/categories")
public class CategoryController {

    private final CatalogQueryService catalog;

    public CategoryController(CatalogQueryService catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    public List<CategoryResponse> list() {
        return catalog.listCategories().stream().map(CategoryResponse::from).toList();
    }
}
