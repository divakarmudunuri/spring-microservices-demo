package com.smd.cartservice.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param endpoint              DynamoDB Local in {@code local}/{@code docker}; empty in AWS (default endpoint)
 * @param tableName             the carts table
 * @param region                AWS region
 * @param createTable           create the table on startup if it's missing (local, docker, tests; in AWS: IaC)
 * @param apiCallTimeout        limit for one SDK call, retries included
 * @param apiCallAttemptTimeout limit for one HTTP attempt
 */
@ConfigurationProperties(prefix = "cart.dynamodb")
public record DynamoDbProperties(URI endpoint, String tableName, String region, boolean createTable,
                                 Duration apiCallTimeout, Duration apiCallAttemptTimeout) {
}
