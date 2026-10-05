package com.smd.productservice.observability;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * Keeps traces about the business: no spans for Prometheus scrapes and health checks ({@code /actuator}) or for
 * Eureka's registry traffic ({@code /eureka}), which would otherwise bury the real requests in Jaeger at 100%
 * sampling in {@code local}. (Security filter-chain and {@code @Scheduled} spans are switched off in
 * application.yml.) Disabling an observation also drops its metrics, which is what we want for these paths.
 */
@Configuration(proxyBeanMethods = false)
class ObservabilityConfig {

    @Bean
    ObservationPredicate skipInfrastructureTraffic() {
        return (name, context) -> {
            if (context instanceof ServerRequestObservationContext server && server.getCarrier() != null) {
                return !isInfrastructure(server.getCarrier().getRequestURI());
            }
            if (context instanceof ClientRequestObservationContext client && client.getCarrier() != null) {
                return !isInfrastructure(client.getCarrier().getURI().getPath());
            }
            return true;
        };
    }

    private static boolean isInfrastructure(String path) {
        return path != null && (path.startsWith("/actuator") || path.startsWith("/eureka"));
    }
}
