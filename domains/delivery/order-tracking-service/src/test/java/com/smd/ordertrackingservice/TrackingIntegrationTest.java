package com.smd.ordertrackingservice;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Real Kafka and DynamoDB Local (one of each for the whole run). Tests use fresh order ids,
 * so they never need to clean up.
 */
@SpringBootTest(properties = {"eureka.client.enabled=false", "tracking.dynamodb.create-table=true"})
@AutoConfigureMockMvc
public abstract class TrackingIntegrationTest {

    @ServiceConnection
    protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

    protected static final GenericContainer<?> DYNAMODB = new GenericContainer<>("amazon/dynamodb-local:3.3.1")
            .withCommand("-jar", "DynamoDBLocal.jar", "-inMemory", "-sharedDb")
            .withExposedPorts(8000);

    static {
        KAFKA.start();
        DYNAMODB.start();
    }

    @DynamicPropertySource
    static void dynamoDb(DynamicPropertyRegistry registry) {
        registry.add("tracking.dynamodb.endpoint",
                () -> "http://" + DYNAMODB.getHost() + ":" + DYNAMODB.getMappedPort(8000));
    }
}
