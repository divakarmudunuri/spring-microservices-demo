package com.smd.cartservice.cart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.smd.cartservice.CartIntegrationTest;
import com.smd.cartservice.persistence.CartLineItem;
import com.smd.cartservice.persistence.CartRepository;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/** CLAUDE.md 6.12: after ORDER_CONFIRMED the ordered quantities leave the cart, exactly once. */
class CheckoutClearingTest extends CartIntegrationTest {

    static final KafkaProducer<String, String> PRODUCER = new KafkaProducer<>(
            Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()),
            new StringSerializer(), new StringSerializer());

    @Autowired
    CartService carts;

    @Autowired
    CartRepository repository;

    @AfterAll
    static void close() {
        PRODUCER.close();
    }

    @Test
    void orderedQuantitiesAreRemovedOnceAndLaterAdditionsStay() {
        UUID customer = UUID.randomUUID();
        carts.addItem(CartOwner.customer(customer), EARBUDS, 3);
        String cartId = carts.addItem(CartOwner.customer(customer), CHARGER, 1).cartId();
        UUID eventId = UUID.randomUUID();

        // the order took 2 earbuds and the charger; one earbud was added after checkout started
        publish(eventId, cartId, Map.of(EARBUDS, 2, CHARGER, 1));
        await().atMost(Duration.ofSeconds(20)).until(() -> quantities(cartId).equals(Map.of(EARBUDS.toString(), 1)));

        publish(eventId, cartId, Map.of(EARBUDS, 2, CHARGER, 1));   // redelivery: nothing more is removed
        UUID marker = UUID.randomUUID();
        publish(marker, UUID.randomUUID().toString(), Map.of(EARBUDS, 1));   // processed after it: proves it was consumed
        await().atMost(Duration.ofSeconds(20)).until(() -> markerExists(marker));
        assertThat(quantities(cartId)).isEqualTo(Map.of(EARBUDS.toString(), 1));
    }

    @Test
    void aCartLeftEmptyIsDeleted() {
        UUID customer = UUID.randomUUID();
        String cartId = carts.addItem(CartOwner.customer(customer), KEYBOARD, 1).cartId();

        publish(UUID.randomUUID(), cartId, Map.of(KEYBOARD, 1));

        await().atMost(Duration.ofSeconds(20)).until(() -> repository.find(cartId).isEmpty());
    }

    @Autowired
    DynamoDbClient dynamo;

    /** The processed-event marker lives in the same table: {@code PK = EVENT#<eventId>}. */
    private boolean markerExists(UUID eventId) {
        return dynamo.getItem(r -> r.tableName("carts").consistentRead(true)
                .key(Map.of("PK", AttributeValue.builder().s("EVENT#" + eventId).build()))).hasItem();
    }

    private Map<String, Integer> quantities(String cartId) {
        return repository.find(cartId).orElseThrow().getItems().stream()
                .collect(Collectors.toMap(CartLineItem::getProductId, CartLineItem::getQuantity));
    }

    private static void publish(UUID eventId, String cartId, Map<UUID, Integer> items) {
        String lines = items.entrySet().stream()
                .map(e -> "{\"productId\":\"%s\",\"quantity\":%d,\"unitPrice\":1.00}".formatted(e.getKey(), e.getValue()))
                .collect(Collectors.joining(","));
        UUID orderId = UUID.randomUUID();
        PRODUCER.send(new ProducerRecord<>("order-events", orderId.toString(), """
                {"eventId":"%s","eventType":"ORDER_CONFIRMED","orderId":"%s","userId":"%s","occurredAt":"2026-10-05T12:00:00Z",
                 "source":"order-service","version":1,"payload":{"items":[%s],"totalAmount":1.00,"currency":"USD",
                 "shippingAddress":{},"cartId":"%s"}}""".formatted(eventId, orderId, UUID.randomUUID(), lines, cartId)));
        PRODUCER.flush();
    }
}
