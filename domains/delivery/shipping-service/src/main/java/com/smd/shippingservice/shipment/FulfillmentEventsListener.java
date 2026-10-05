package com.smd.shippingservice.shipment;

import com.smd.shippingservice.events.EventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Only FULFILLMENT_PACKED matters here; every other fulfillment event is skipped. */
@Component
public class FulfillmentEventsListener {

    private static final Logger log = LoggerFactory.getLogger(FulfillmentEventsListener.class);

    private final ShippingService shippingService;

    public FulfillmentEventsListener(ShippingService shippingService) {
        this.shippingService = shippingService;
    }

    @KafkaListener(topics = "fulfillment-events")
    public void onEvent(EventEnvelope event) {
        if ("FULFILLMENT_PACKED".equals(event.eventType())) {
            shippingService.onFulfillmentPacked(event);
        } else {
            log.trace("Ignoring {} {}", event.eventType(), event.eventId());
        }
    }
}
