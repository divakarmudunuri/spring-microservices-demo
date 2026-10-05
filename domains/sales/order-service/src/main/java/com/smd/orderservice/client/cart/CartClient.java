package com.smd.orderservice.client.cart;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Package-private, like its DTOs: only {@link CartAdapter} uses it. Resolved through Eureka. The customer's own cart:
 * cart-service picks it from the relayed JWT, so no id is sent.
 */
@FeignClient(name = "cart-service", configuration = CartClientConfig.class)
interface CartClient {

    @GetMapping("/api/cart")
    CartDto getMyCart();
}
