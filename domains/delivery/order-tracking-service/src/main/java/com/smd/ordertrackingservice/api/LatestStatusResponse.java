package com.smd.ordertrackingservice.api;

import com.smd.ordertrackingservice.persistence.TrackingStateItem;
import java.util.UUID;

public record LatestStatusResponse(UUID orderId, String currentStatus, String lastEventAt) {

    static LatestStatusResponse from(UUID orderId, TrackingStateItem state) {
        return new LatestStatusResponse(orderId, state.getCurrentStatus(), state.getLastEventAt());
    }
}
