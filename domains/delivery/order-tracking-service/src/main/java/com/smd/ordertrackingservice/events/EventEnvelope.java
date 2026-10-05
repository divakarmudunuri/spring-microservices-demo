package com.smd.ordertrackingservice.events;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * This service's own copy of the event envelope (docs/events.md). The payload stays a JsonNode:
 * this service only picks a few details out of it, whatever the event type.
 */
public record EventEnvelope(
        UUID eventId,
        String eventType,
        UUID orderId,
        UUID userId,
        Instant occurredAt,
        String source,
        int version,
        JsonNode payload) {
}
