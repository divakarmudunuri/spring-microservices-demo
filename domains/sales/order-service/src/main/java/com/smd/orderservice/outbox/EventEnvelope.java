package com.smd.orderservice.outbox;

import java.time.Instant;
import java.util.UUID;

/**
 * The JSON envelope of every event this service publishes (contract: docs/events.md).
 * {@code orderId} and {@code userId} are null for {@code inventory-events}.
 */
public record EventEnvelope(
        UUID eventId,
        String eventType,
        UUID orderId,
        UUID userId,
        Instant occurredAt,
        String source,
        int version,
        Object payload) {
}
