package com.smd.cartservice.cart;

import com.fasterxml.jackson.databind.JsonNode;
import com.smd.cartservice.events.EventEnvelope;
import com.smd.cartservice.persistence.CartItem;
import com.smd.cartservice.persistence.CartRepository;
import com.smd.cartservice.persistence.ProcessedEventItem;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Empties the cart after a successful checkout, from {@code ORDER_CONFIRMED} (which carries the {@code cartId} and the
 * ordered items). Only the ordered quantities are removed, so anything added to the cart after checkout started stays.
 * A cart left empty is deleted.
 *
 * <p>Why not clear it from order-service directly: checkout must make no remote call inside its transaction, and a
 * checkout that fails must leave the cart untouched. Reacting to the committed ORDER_CONFIRMED gives both.
 *
 * <p>Idempotent: the marker {@code EVENT#<eventId>} and the cart change are one atomic write, so a redelivered event
 * finds the marker and changes nothing.
 */
@Component
public class CheckoutClearing {

    private static final Logger log = LoggerFactory.getLogger(CheckoutClearing.class);
    static final Duration MARKER_TTL = Duration.ofDays(7);

    private final CartRepository carts;
    private final Clock clock;

    public CheckoutClearing(CartRepository carts, Clock clock) {
        this.carts = carts;
        this.clock = clock;
    }

    @KafkaListener(topics = "order-events")
    public void onEvent(EventEnvelope event) {
        if (!"ORDER_CONFIRMED".equals(event.eventType()) || event.payload() == null
                || !event.payload().hasNonNull("cartId")) {
            return;   // only orders placed from a cart concern us
        }
        clear(event);
    }

    void clear(EventEnvelope event) {
        Instant now = clock.instant();
        ProcessedEventItem marker = new ProcessedEventItem();
        marker.setPk(ProcessedEventItem.pk(event.eventId().toString()));
        marker.setProcessedAt(now.toString());
        marker.setExpiresAt(now.plus(MARKER_TTL).getEpochSecond());

        Optional<CartItem> found = carts.find(event.payload().get("cartId").asText());
        CartItem cart = found.orElse(null);
        if (cart != null) {
            for (JsonNode ordered : event.payload().path("items")) {
                String productId = ordered.get("productId").asText();
                int quantity = ordered.get("quantity").asInt();
                cart.getItems().forEach(line -> {
                    if (line.getProductId().equals(productId)) {
                        line.setQuantity(line.getQuantity() - quantity);
                    }
                });
            }
            cart.getItems().removeIf(line -> line.getQuantity() <= 0);
            cart.setUpdatedAt(now.toString());
        }
        boolean delete = cart != null && cart.getItems().isEmpty();
        // a version conflict (the customer changed the cart meanwhile) propagates: the Kafka error handler retries
        if (!carts.applyCheckout(marker, cart, delete)) {
            log.debug("ORDER_CONFIRMED {} already applied to its cart", event.eventId());
        }
    }
}
