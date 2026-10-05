package com.smd.orderservice.client.product;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Resolved through Eureka + Spring Cloud LoadBalancer: no URL here. Package-private, like its DTOs:
 * only {@link ProductAdapter} can use it, so downstream DTOs never leak into the rest of the service.
 */
@FeignClient(name = "product-service")
interface ProductClient {

    /** Batch lookup; unknown or inactive ids are simply missing from the result. */
    @GetMapping("/api/products")
    List<ProductDto> getProducts(@RequestParam("ids") Collection<UUID> ids);
}
