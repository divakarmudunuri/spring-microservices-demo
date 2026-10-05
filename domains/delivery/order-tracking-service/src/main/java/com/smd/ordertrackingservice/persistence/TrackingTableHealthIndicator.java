package com.smd.ordertrackingservice.persistence;

import com.smd.ordertrackingservice.config.DynamoDbProperties;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.TableStatus;

/** Readiness: the tracking table must exist and be ACTIVE. Shown as {@code trackingTable} in /actuator/health. */
@Component("trackingTable")
public class TrackingTableHealthIndicator implements HealthIndicator {

    private final DynamoDbClient dynamo;
    private final DynamoDbProperties properties;

    public TrackingTableHealthIndicator(DynamoDbClient dynamo, DynamoDbProperties properties) {
        this.dynamo = dynamo;
        this.properties = properties;
    }

    @Override
    public Health health() {
        try {
            TableStatus status = dynamo.describeTable(r -> r.tableName(properties.tableName())).table().tableStatus();
            Health.Builder health = status == TableStatus.ACTIVE ? Health.up() : Health.down();
            return health.withDetail("table", properties.tableName()).withDetail("status", status.toString()).build();
        } catch (RuntimeException e) {
            return Health.down(e).withDetail("table", properties.tableName()).build();
        }
    }
}
