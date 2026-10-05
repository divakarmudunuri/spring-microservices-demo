package com.smd.orderservice.outbox;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * The producing service creates its topics on startup (automatic topic creation is off on the broker).
 * Locally: 3 partitions, replication factor 1.
 */
@Configuration
public class KafkaTopicsConfig {

    @Bean
    NewTopic orderEventsTopic(@Value("${kafka.topics.partitions}") int partitions,
                              @Value("${kafka.topics.replicas}") int replicas) {
        return TopicBuilder.name(Topics.ORDER_EVENTS).partitions(partitions).replicas(replicas).build();
    }

    @Bean
    NewTopic inventoryEventsTopic(@Value("${kafka.topics.partitions}") int partitions,
                                  @Value("${kafka.topics.replicas}") int replicas) {
        return TopicBuilder.name(Topics.INVENTORY_EVENTS).partitions(partitions).replicas(replicas).build();
    }
}
