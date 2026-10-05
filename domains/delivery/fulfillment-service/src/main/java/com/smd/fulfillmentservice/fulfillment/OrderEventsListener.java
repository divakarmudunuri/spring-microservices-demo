package com.smd.fulfillmentservice.fulfillment;

import com.smd.fulfillmentservice.events.EventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Only ORDER_CONFIRMED matters here; every other order event is skipped. Acknowledged after the DB commit. */
@Component
public class OrderEventsListener {

    private static final Logger log = LoggerFactory.getLogger(OrderEventsListener.class);

    private final FulfillmentService fulfillmentService;

    public OrderEventsListener(FulfillmentService fulfillmentService) {
        this.fulfillmentService = fulfillmentService;
    }

    @KafkaListener(topics = "order-events")
    public void onEvent(EventEnvelope event) {
        if ("ORDER_CONFIRMED".equals(event.eventType())) {
            fulfillmentService.onOrderConfirmed(event);
        } else {
            log.trace("Ignoring {} {}", event.eventType(), event.eventId());
        }
    }
}
