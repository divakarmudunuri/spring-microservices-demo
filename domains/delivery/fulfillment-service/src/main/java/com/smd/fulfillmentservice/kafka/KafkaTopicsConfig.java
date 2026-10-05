package com.smd.fulfillmentservice.kafka;

import com.smd.fulfillmentservice.outbox.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** The producing service creates its topic on startup (automatic topic creation is off on the broker). */
@Configuration
public class KafkaTopicsConfig {

    @Bean
    NewTopic fulfillmentEventsTopic(@Value("${kafka.topics.partitions}") int partitions,
                                    @Value("${kafka.topics.replicas}") int replicas) {
        return TopicBuilder.name(Topics.FULFILLMENT_EVENTS).partitions(partitions).replicas(replicas).build();
    }
}
