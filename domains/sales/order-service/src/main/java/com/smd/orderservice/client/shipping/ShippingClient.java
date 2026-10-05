package com.smd.orderservice.client.shipping;

import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/** Package-private, like its DTOs: only {@link ShippingAdapter} uses it. Resolved through Eureka. */
@FeignClient(name = "shipping-service", configuration = ShippingClientConfig.class)
interface ShippingClient {

    @GetMapping("/api/shipments/by-order/{orderId}")
    ShipmentDto getShipment(@PathVariable("orderId") UUID orderId);
}
