package com.smd.ordertrackingservice.api;

import java.util.UUID;

public class TrackingNotFoundException extends RuntimeException {

    public TrackingNotFoundException(UUID orderId) {
        super("No tracking information for order " + orderId);
    }
}
