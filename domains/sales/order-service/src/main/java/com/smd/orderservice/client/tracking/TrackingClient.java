package com.smd.orderservice.client.tracking;

import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/** Package-private, like its DTOs: only {@link TrackingAdapter} uses it. Resolved through Eureka. */
@FeignClient(name = "order-tracking-service", configuration = TrackingClientConfig.class)
interface TrackingClient {

    @GetMapping("/api/tracking/orders/{orderId}")
    TrackingDto getTracking(@PathVariable("orderId") UUID orderId);
}
