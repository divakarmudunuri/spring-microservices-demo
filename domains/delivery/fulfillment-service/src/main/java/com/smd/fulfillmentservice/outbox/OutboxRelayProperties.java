package com.smd.fulfillmentservice.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param enabled     run the scheduled poll (tests turn it off and call {@link OutboxRelay#poll()} themselves)
 * @param interval    pause between two polls
 * @param batchSize   rows per poll
 * @param sendTimeout how long to wait for the broker to acknowledge one record
 */
@ConfigurationProperties(prefix = "outbox.relay")
public record OutboxRelayProperties(boolean enabled, Duration interval, int batchSize, Duration sendTimeout) {
}
