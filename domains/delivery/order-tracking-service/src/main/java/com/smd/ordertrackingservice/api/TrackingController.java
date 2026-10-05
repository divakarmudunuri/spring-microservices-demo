package com.smd.ordertrackingservice.api;

import com.smd.ordertrackingservice.tracking.TrackingService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * TODO(phase-11): a CUSTOMER may read only orders whose STATE.userId is their JWT sub (404 otherwise);
 * ADMIN may read any, plus {@code GET /api/admin/tracking/orders/{orderId}}.
 */
@RestController
@RequestMapping("/api/tracking/orders")
public class TrackingController {

    private final TrackingService tracking;

    public TrackingController(TrackingService tracking) {
        this.tracking = tracking;
    }

    /** Current status and the full timeline, from one DynamoDB Query. */
    @GetMapping("/{orderId}")
    public TrackingResponse timeline(@PathVariable UUID orderId) {
        return tracking.timeline(orderId)
                .map(t -> TrackingResponse.from(orderId, t))
                .orElseThrow(() -> new TrackingNotFoundException(orderId));
    }

    /** Only the current status (strongly consistent read); used by order-service's aggregator. */
    @GetMapping("/{orderId}/latest")
    public LatestStatusResponse latest(@PathVariable UUID orderId) {
        return tracking.latest(orderId)
                .map(s -> LatestStatusResponse.from(orderId, s))
                .orElseThrow(() -> new TrackingNotFoundException(orderId));
    }
}
