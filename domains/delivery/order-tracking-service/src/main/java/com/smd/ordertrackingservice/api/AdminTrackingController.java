package com.smd.ordertrackingservice.api;

import com.smd.ordertrackingservice.tracking.TrackingService;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admins: the timeline of any order. There is deliberately no "list all orders" here: that would be a full-table
 * DynamoDB {@code Scan}. Admins list orders from order-service ({@code GET /api/admin/orders}, Postgres, indexed)
 * and open one timeline here.
 */
@RestController
@RequestMapping("/api/admin/tracking/orders")
@PreAuthorize("hasRole('ADMIN')")
public class AdminTrackingController {

    private final TrackingService tracking;

    public AdminTrackingController(TrackingService tracking) {
        this.tracking = tracking;
    }

    @GetMapping("/{orderId}")
    public TrackingResponse timeline(@PathVariable UUID orderId) {
        return tracking.timeline(orderId)
                .map(t -> TrackingResponse.from(orderId, t))
                .orElseThrow(() -> new TrackingNotFoundException(orderId));
    }
}
