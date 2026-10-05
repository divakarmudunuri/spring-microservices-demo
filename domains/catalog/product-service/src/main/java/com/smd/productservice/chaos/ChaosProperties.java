package com.smd.productservice.chaos;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Starting values for the chaos toggles ({@code local} profile only). They can be changed
 * at runtime through {@code POST /internal/chaos}; see {@link ChaosSettings}.
 *
 * @param latencyMs   extra delay added to every {@code /api/**} request
 * @param failureRate share of {@code /api/**} requests that fail with a 500, from 0.0 to 1.0
 */
@ConfigurationProperties(prefix = "demo.chaos")
public record ChaosProperties(long latencyMs, double failureRate) {
}
