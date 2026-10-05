package com.smd.ordertrackingservice.api;

import com.smd.ordertrackingservice.persistence.OrderTimeline;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record TrackingResponse(UUID orderId, String currentStatus, String lastEventAt, List<TimelineEntry> timeline) {

    public record TimelineEntry(String status, String source, String occurredAt, Map<String, String> details) {
    }

    static TrackingResponse from(UUID orderId, OrderTimeline t) {
        return new TrackingResponse(orderId, t.state().getCurrentStatus(), t.state().getLastEventAt(),
                t.events().stream()
                        .map(e -> new TimelineEntry(e.getStatus(), e.getSourceService(), e.getOccurredAt(),
                                e.getDetails() == null ? Map.of() : e.getDetails()))
                        .toList());
    }
}
