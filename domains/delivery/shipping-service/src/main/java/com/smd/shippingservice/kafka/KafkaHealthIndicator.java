package com.smd.shippingservice.kafka;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

/**
 * Readiness includes Kafka (CLAUDE.md 6.10). Spring Boot has no Kafka health indicator, so this asks the broker
 * for its cluster id with a short timeout. It's in the {@code readiness} group only: a broker outage takes the
 * instance out of rotation but doesn't get it restarted (liveness stays UP).
 */
@Component("kafka")
class KafkaHealthIndicator extends AbstractHealthIndicator implements DisposableBean {

    private static final int TIMEOUT_MS = 3000;

    private final KafkaAdmin kafkaAdmin;
    private AdminClient admin;   // created on the first check, so tests and startup don't open an extra client

    KafkaHealthIndicator(KafkaAdmin kafkaAdmin) {
        super("Kafka is not reachable");
        this.kafkaAdmin = kafkaAdmin;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) throws Exception {
        String clusterId = admin().describeCluster(new DescribeClusterOptions().timeoutMs(TIMEOUT_MS))
                .clusterId().get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        builder.up().withDetail("clusterId", clusterId);
    }

    private synchronized AdminClient admin() {
        if (admin == null) {
            admin = AdminClient.create(kafkaAdmin.getConfigurationProperties());
        }
        return admin;
    }

    @Override
    public synchronized void destroy() {
        if (admin != null) {
            admin.close(Duration.ofSeconds(1));
        }
    }
}
