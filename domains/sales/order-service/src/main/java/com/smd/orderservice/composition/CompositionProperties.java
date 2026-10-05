package com.smd.orderservice.composition;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param corePoolSize  threads kept alive
 * @param maxPoolSize   upper bound, used only once the queue is full
 * @param queueCapacity tasks waiting for a thread
 * @param deadline      overall time limit for one parallel call, on top of the Feign timeouts
 */
@ConfigurationProperties(prefix = "composition")
public record CompositionProperties(int corePoolSize, int maxPoolSize, int queueCapacity, Duration deadline) {
}
