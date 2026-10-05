package com.smd.orderservice.events;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/** An event this service consumes (fulfillment-events, shipping-events), in its own class (docs/events.md). */
public record ConsumedEvent(
        UUID eventId,
        String eventType,
        UUID orderId,
        UUID userId,
        Instant occurredAt,
        String source,
        int version,
        JsonNode payload) {
}
