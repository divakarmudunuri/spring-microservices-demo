package com.smd.ordertrackingservice.events;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration
public class KafkaConsumerConfig {

    /**
     * A failing record is retried with exponential backoff (3 attempts in total), then published to
     * {@code <topic>.DLT} (same partition) for manual inspection, and the partition moves on.
     * Records that can never succeed skip the retries: deserialization errors (from the
     * ErrorHandlingDeserializer) are non-retryable by default, and so is {@link InvalidEventException}.
     * Boot plugs this bean into the default listener container factory.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(ProducerFactory<?, ?> bootProducerFactory) {
        ExponentialBackOff backOff = new ExponentialBackOff(500, 2.0);
        backOff.setMaxAttempts(2);   // retries after the first attempt → 3 attempts
        // explicit name: the recoverer's own default suffix differs between Spring Kafka versions (".DLT" / "-dlt")
        var recoverer = new DeadLetterPublishingRecoverer(deadLetterTemplate(bootProducerFactory),
                (record, failure) -> new TopicPartition(deadLetterTopic(record.topic()), record.partition()));
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(InvalidEventException.class);
        return handler;
    }

    /**
     * Publishes to the DLTs. Failed records keep their value: a deserialized envelope as JSON, undeserializable
     * raw bytes unchanged. Deliberately not a bean: a KafkaTemplate bean of our own would make Spring Boot
     * skip its default one (which the outbox relay uses).
     */
    private static KafkaTemplate<String, Object> deadLetterTemplate(ProducerFactory<?, ?> bootProducerFactory) {
        Map<Class<?>, Serializer<?>> byType = new LinkedHashMap<>();
        byType.put(byte[].class, new ByteArraySerializer());
        byType.put(Object.class, new JsonSerializer<>());
        // start from Boot's producer settings: they include the resolved broker address (connection details)
        var factory = new DefaultKafkaProducerFactory<>(bootProducerFactory.getConfigurationProperties(),
                new StringSerializer(), new DelegatingByTypeSerializer(byType, true));
        return new KafkaTemplate<>(factory);
    }

    /** {@code <topic>.DLT} for every consumed topic, with the same partition count (the recoverer keeps the partition). */
    @Bean
    KafkaAdmin.NewTopics deadLetterTopics(@Value("${tracking.topics}") String topics,
                                          @Value("${kafka.topics.partitions}") int partitions,
                                          @Value("${kafka.topics.replicas}") int replicas) {
        return new KafkaAdmin.NewTopics(Arrays.stream(topics.split(","))
                .map(t -> TopicBuilder.name(deadLetterTopic(t.trim())).partitions(partitions).replicas(replicas).build())
                .toArray(NewTopic[]::new));
    }

    static String deadLetterTopic(String topic) {
        return topic + ".DLT";
    }
}
