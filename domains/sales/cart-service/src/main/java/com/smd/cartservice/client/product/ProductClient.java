package com.smd.cartservice.client.product;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Package-private, like its DTOs: only {@link ProductAdapter} uses it. Resolved through Eureka. */
@FeignClient(name = "product-service", configuration = ProductClientConfig.class)
interface ProductClient {

    /** The public batch lookup; unknown or inactive ids are missing from the result. */
    @GetMapping("/api/products")
    List<ProductDto> getProducts(@RequestParam("ids") Collection<UUID> ids);
}
