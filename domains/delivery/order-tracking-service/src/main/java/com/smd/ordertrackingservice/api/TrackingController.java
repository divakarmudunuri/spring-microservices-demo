package com.smd.ordertrackingservice.api;

import com.smd.ordertrackingservice.security.CurrentUser;
import com.smd.ordertrackingservice.tracking.TrackingService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A CUSTOMER may read only orders whose STATE.userId is their JWT {@code sub}; someone else's order is a 404
 * (not 403), so ids can't be probed. An ADMIN may read any (e.g. through order-service's aggregator).
 */
@RestController
@RequestMapping("/api/tracking/orders")
public class TrackingController {

    private final TrackingService tracking;
    private final CurrentUser currentUser;

    public TrackingController(TrackingService tracking, CurrentUser currentUser) {
        this.tracking = tracking;
        this.currentUser = currentUser;
    }

    /** Current status and the full timeline, from one DynamoDB Query. */
    @GetMapping("/{orderId}")
    public TrackingResponse timeline(@PathVariable UUID orderId) {
        return tracking.timeline(orderId)
                .filter(t -> currentUser.mayRead(UUID.fromString(t.state().getUserId())))
                .map(t -> TrackingResponse.from(orderId, t))
                .orElseThrow(() -> new TrackingNotFoundException(orderId));
    }

    /** Only the current status (strongly consistent read); used by order-service's aggregator. */
    @GetMapping("/{orderId}/latest")
    public LatestStatusResponse latest(@PathVariable UUID orderId) {
        return tracking.latest(orderId)
                .filter(s -> currentUser.mayRead(UUID.fromString(s.getUserId())))
                .map(s -> LatestStatusResponse.from(orderId, s))
                .orElseThrow(() -> new TrackingNotFoundException(orderId));
    }
}
