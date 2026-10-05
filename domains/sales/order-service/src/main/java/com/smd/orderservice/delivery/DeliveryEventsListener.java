package com.smd.orderservice.delivery;

import com.smd.orderservice.events.ConsumedEvent;
import com.smd.orderservice.order.OrderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Consumes fulfillment-events and shipping-events (consumer group order-service). */
@Component
public class DeliveryEventsListener {

    private static final Logger log = LoggerFactory.getLogger(DeliveryEventsListener.class);

    private final OrderProgressService progress;

    public DeliveryEventsListener(OrderProgressService progress) {
        this.progress = progress;
    }

    @KafkaListener(topics = {"fulfillment-events", "shipping-events"})
    public void onEvent(ConsumedEvent event) {
        switch (event.eventType()) {
            case "FULFILLMENT_RECEIVED" -> progress.apply(event, OrderStatus.IN_FULFILLMENT);
            case "SHIPMENT_PICKED_UP" -> progress.apply(event, OrderStatus.SHIPPED);
            case "SHIPMENT_DELIVERED" -> progress.apply(event, OrderStatus.DELIVERED);
            // TODO(phase-8): FULFILLMENT_FAILED → refund + restock (OrderCancellationService)
            default -> log.trace("Ignoring {} {}", event.eventType(), event.eventId());
        }
    }
}
