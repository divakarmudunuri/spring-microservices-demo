package com.smd.shippingservice;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Real Postgres and Kafka. The scheduled relay and simulator are off: tests call them, so nothing runs in
 * the background against the shared database. Step delay 0: every shipment is due immediately.
 */
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "outbox.relay.enabled=false",
        "demo.simulation.enabled=false",
        "demo.simulation.step-delay=0s"
})
public abstract class ShippingIntegrationTest {

    protected static final UUID CUSTOMER = UUID.fromString("00000000-0000-4000-8000-0000000000c1");

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.15")
            .withDatabaseName("shipping_db").withUsername("shipping_svc");

    @ServiceConnection
    protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

    protected static final KafkaProducer<String, String> PRODUCER;

    static {
        POSTGRES.start();
        KAFKA.start();
        PRODUCER = new KafkaProducer<>(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()),
                new StringSerializer(), new StringSerializer());
    }

    @Autowired
    protected JdbcClient jdbc;

    @BeforeEach
    void resetDatabase() {
        jdbc.sql("TRUNCATE shipments, outbox_event, processed_event").update();
    }

    protected static void publish(String topic, UUID key, String json) {
        PRODUCER.send(new ProducerRecord<>(topic, key.toString(), json));
        PRODUCER.flush();
    }

    protected static String fulfillmentPacked(UUID eventId, UUID orderId) {
        return """
                {"eventId":"%s","eventType":"FULFILLMENT_PACKED","orderId":"%s","userId":"%s",
                 "occurredAt":"2026-10-05T10:00:04Z","source":"fulfillment-service","version":1,
                 "payload":{"fulfillmentId":"%s","warehouseCode":"WH-DETROIT-1",
                            "shippingAddress":{"fullName":"Demo Customer","line1":"100 Example Street","line2":"Apt 4B",
                                               "city":"Detroit","state":"MI","postalCode":"48226","country":"US",
                                               "phone":"+1-555-0100"}}}""".formatted(eventId, orderId, CUSTOMER, UUID.randomUUID());
    }

    protected List<String> outboxTypes(UUID orderId) {
        return jdbc.sql("SELECT event_type FROM outbox_event WHERE aggregate_id = ? ORDER BY created_at")
                .param(orderId).query(String.class).list();
    }

    protected static KafkaConsumer<String, String> consumer(String topic) {
        var consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"), new StringDeserializer(), new StringDeserializer());
        consumer.subscribe(List.of(topic));
        return consumer;
    }

    protected static void pollInto(KafkaConsumer<String, String> consumer, List<ConsumerRecord<String, String>> into) {
        consumer.poll(Duration.ofMillis(200)).forEach(into::add);
    }
}
