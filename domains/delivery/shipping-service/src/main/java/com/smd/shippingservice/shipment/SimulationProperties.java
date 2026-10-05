package com.smd.shippingservice.shipment;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param enabled         run the scheduled simulator (tests turn it off and call {@link ShipmentSimulator#advanceDue()})
 * @param stepDelay       time between two status changes (same setting as fulfillment-service)
 * @param pollInterval    how often the simulator looks for due shipments
 * @param carrier         the (simulated) carrier name
 * @param deliveryDays    days from label to estimated delivery
 */
@ConfigurationProperties(prefix = "demo.simulation")
public record SimulationProperties(boolean enabled, Duration stepDelay, Duration pollInterval, String carrier,
                                   int deliveryDays) {
}
