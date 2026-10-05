package com.smd.fulfillmentservice.fulfillment;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param enabled       run the scheduled simulator (tests turn it off and call {@link FulfillmentSimulator#advanceDue()})
 * @param stepDelay     time between two status changes
 * @param failureRate   share of fulfillments that fail (FULFILLMENT_FAILED), 0.0 to 1.0
 * @param pollInterval  how often the simulator looks for due fulfillments
 * @param warehouseCode the (single, simulated) warehouse
 */
@ConfigurationProperties(prefix = "demo.simulation")
public record SimulationProperties(boolean enabled, Duration stepDelay, double failureRate, Duration pollInterval,
                                   String warehouseCode) {
}
