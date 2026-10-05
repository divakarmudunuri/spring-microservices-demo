package com.smd.cartservice.events;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/** This service's own copy of the event envelope (docs/events.md), used for consumed and published events. */
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
