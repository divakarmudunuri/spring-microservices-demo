package com.smd.productservice.availability;

import com.smd.productservice.catalog.AvailabilityLevel;
import com.smd.productservice.events.EventEnvelope;
import com.smd.productservice.events.InvalidEventException;
import java.time.ZoneOffset;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps {@code product_availability} (the public stock level) in step with order-service's exact stock, from
 * {@code INVENTORY_CHANGED} events.
 *
 * <p><b>Eventually consistent:</b> the level can lag a stock change by a moment. That's acceptable because the
 * authoritative check is the checkout transaction in order-service: the worst case is "shown in stock, rejected at
 * checkout with 409 OUT_OF_STOCK", never an oversold product.
 */
@Service
public class AvailabilityService {

    private static final Logger log = LoggerFactory.getLogger(AvailabilityService.class);

    private final JdbcClient jdbc;
    private final AvailabilityLevels levels;

    public AvailabilityService(JdbcClient jdbc, AvailabilityLevels levels) {
        this.jdbc = jdbc;
        this.levels = levels;
    }

    /**
     * One transaction: the processed-event marker and the upsert. A redelivered event is skipped; an event older than
     * the last one applied ({@code last_event_at}) changes nothing, so events arriving out of order can't move the
     * level back to a stale value.
     *
     * @return true if the level row was written (the caches must then be evicted)
     */
    @Transactional
    public boolean apply(EventEnvelope event) {
        if (event.eventId() == null || event.occurredAt() == null || event.payload() == null
                || !event.payload().hasNonNull("productId") || !event.payload().hasNonNull("quantityOnHand")) {
            throw new InvalidEventException("INVENTORY_CHANGED without eventId, occurredAt, productId or quantityOnHand");
        }
        int fresh = jdbc.sql("INSERT INTO processed_event (event_id) VALUES (:id) ON CONFLICT DO NOTHING")
                .param("id", event.eventId()).update();
        if (fresh == 0) {
            log.debug("Skipping duplicate event {}", event.eventId());
            return false;
        }
        UUID productId = UUID.fromString(event.payload().get("productId").asText());
        boolean known = jdbc.sql("SELECT count(*) FROM products WHERE id = :id").param("id", productId)
                .query(Integer.class).single() > 0;
        if (!known) {
            log.warn("INVENTORY_CHANGED for unknown product {}; ignored", productId);
            return false;
        }
        AvailabilityLevel level = levels.levelFor(event.payload().get("quantityOnHand").asInt());
        int written = jdbc.sql("""
                        INSERT INTO product_availability (product_id, level, last_event_at)
                        VALUES (:productId, :level, :at)
                        ON CONFLICT (product_id) DO UPDATE
                           SET level = EXCLUDED.level, last_event_at = EXCLUDED.last_event_at
                         WHERE product_availability.last_event_at < EXCLUDED.last_event_at""")
                .param("productId", productId)
                .param("level", level.name())
                .param("at", event.occurredAt().atOffset(ZoneOffset.UTC))
                .update();
        if (written == 0) {
            log.debug("INVENTORY_CHANGED {} is older than the level already applied for {}; ignored", event.eventId(), productId);
        }
        return written == 1;
    }
}
