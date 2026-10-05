package com.smd.productservice.availability;

import com.smd.productservice.api.CatalogCache;
import com.smd.productservice.events.EventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Consumes {@code inventory-events} (consumer group product-service). order-tracking-service doesn't consume it. */
@Component
public class InventoryEventsListener {

    private static final Logger log = LoggerFactory.getLogger(InventoryEventsListener.class);

    private final AvailabilityService availability;
    private final CatalogCache cache;

    public InventoryEventsListener(AvailabilityService availability, CatalogCache cache) {
        this.availability = availability;
        this.cache = cache;
    }

    @KafkaListener(topics = "inventory-events")
    public void onEvent(EventEnvelope event) {
        if (!"INVENTORY_CHANGED".equals(event.eventType())) {
            log.trace("Ignoring {} {}", event.eventType(), event.eventId());
            return;
        }
        // evict after apply() returned, i.e. after its transaction committed: a concurrent read can't cache the old level again
        if (availability.apply(event)) {
            cache.evictProducts();
        }
    }
}
