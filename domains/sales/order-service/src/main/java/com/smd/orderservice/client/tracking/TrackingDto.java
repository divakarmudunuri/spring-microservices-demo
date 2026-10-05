package com.smd.orderservice.client.tracking;

import java.util.List;
import java.util.Map;

/** order-tracking-service's response, as this service needs it. Never leaves this package. */
record TrackingDto(String currentStatus, String lastEventAt, List<Entry> timeline) {

    record Entry(String status, String source, String occurredAt, Map<String, String> details) {
    }
}
