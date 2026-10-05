package com.smd.orderservice.client.tracking;

import java.util.List;
import java.util.Map;

/** Latest status and timeline of an order, as the order-details view shows them. */
public record Tracking(String currentStatus, List<Entry> timeline) {

    public record Entry(String status, String source, String occurredAt, Map<String, String> details) {
    }
}
