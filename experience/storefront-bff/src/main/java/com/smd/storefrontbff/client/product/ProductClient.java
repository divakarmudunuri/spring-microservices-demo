package com.smd.storefrontbff.client.product;

import java.util.List;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/** Package-private, like its DTOs: only {@link ProductAdapter} uses it. product-service's public catalog. */
@FeignClient(name = "product-service", configuration = ProductClientConfig.class)
interface ProductClient {

    @GetMapping("/api/categories")
    List<CategoryDto> categories();

    @GetMapping("/api/products")
    ProductPageDto products(@RequestParam(value = "category", required = false) String category,
                            @RequestParam(value = "featured", required = false) Boolean featured,
                            @RequestParam(value = "sort", required = false) String sort,
                            @RequestParam("size") int size);

    @GetMapping("/api/products/{slug}")
    ProductDto product(@PathVariable("slug") String slug);
}
