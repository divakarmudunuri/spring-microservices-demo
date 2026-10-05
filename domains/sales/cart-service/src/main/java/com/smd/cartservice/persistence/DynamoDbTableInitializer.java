package com.smd.cartservice.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smd.cartservice.config.DynamoDbProperties;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;

/**
 * Creates the carts table (with its {@code byOwner} index and TTL on {@code expiresAt}) on startup if it's missing,
 * when {@code cart.dynamodb.create-table=true}
 * (the {@code local} and {@code docker} profiles, and tests). In real AWS the table comes from
 * infrastructure as code, not from the application.
 *
 * <p>The definition is {@code classpath:dynamodb/carts.table.json}, a copy of the reviewed
 * {@code data-model/dynamodb/carts.table.json} (a test keeps the two identical).
 */
@Component("dynamoDbTableInitializer")
public class DynamoDbTableInitializer implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(DynamoDbTableInitializer.class);
    static final String DEFINITION = "dynamodb/carts.table.json";
    static final String TTL_ATTRIBUTE = "expiresAt";

    private final DynamoDbClient dynamo;
    private final DynamoDbProperties properties;
    private final ObjectMapper objectMapper;

    public DynamoDbTableInitializer(DynamoDbClient dynamo, DynamoDbProperties properties, ObjectMapper objectMapper) {
        this.dynamo = dynamo;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterPropertiesSet() throws IOException {
        if (!properties.createTable()) {
            return;
        }
        String table = properties.tableName();
        try {
            dynamo.describeTable(r -> r.tableName(table));
            log.info("DynamoDB table {} exists", table);
            return;
        } catch (ResourceNotFoundException e) {
            // create it below
        }
        dynamo.createTable(fromDefinition(table));
        dynamo.waiter().waitUntilTableExists(r -> r.tableName(table));
        dynamo.updateTimeToLive(r -> r.tableName(table)
                .timeToLiveSpecification(t -> t.attributeName(TTL_ATTRIBUTE).enabled(true)));
        log.info("Created DynamoDB table {} (TTL on {})", table, TTL_ATTRIBUTE);
    }

    private CreateTableRequest fromDefinition(String table) throws IOException {
        JsonNode definition;
        try (InputStream in = new ClassPathResource(DEFINITION).getInputStream()) {
            definition = objectMapper.readTree(in);
        }
        List<AttributeDefinition> attributes = new ArrayList<>();
        definition.get("AttributeDefinitions").forEach(a -> attributes.add(AttributeDefinition.builder()
                .attributeName(a.get("AttributeName").asText()).attributeType(a.get("AttributeType").asText()).build()));
        List<KeySchemaElement> keys = new ArrayList<>();
        definition.get("KeySchema").forEach(k -> keys.add(KeySchemaElement.builder()
                .attributeName(k.get("AttributeName").asText()).keyType(k.get("KeyType").asText()).build()));
        List<GlobalSecondaryIndex> indexes = new ArrayList<>();
        definition.path("GlobalSecondaryIndexes").forEach(index -> {
            List<KeySchemaElement> indexKeys = new ArrayList<>();
            index.get("KeySchema").forEach(k -> indexKeys.add(KeySchemaElement.builder()
                    .attributeName(k.get("AttributeName").asText()).keyType(k.get("KeyType").asText()).build()));
            indexes.add(GlobalSecondaryIndex.builder()
                    .indexName(index.get("IndexName").asText())
                    .keySchema(indexKeys)
                    .projection(Projection.builder().projectionType(index.at("/Projection/ProjectionType").asText()).build())
                    .build());
        });
        return CreateTableRequest.builder()
                .tableName(table)
                .billingMode(definition.get("BillingMode").asText())
                .attributeDefinitions(attributes)
                .keySchema(keys)
                .globalSecondaryIndexes(indexes)
                .build();
    }
}
