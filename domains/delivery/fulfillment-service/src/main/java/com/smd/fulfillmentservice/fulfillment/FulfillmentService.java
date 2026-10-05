package com.smd.fulfillmentservice.fulfillment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smd.fulfillmentservice.events.EventEnvelope;
import com.smd.fulfillmentservice.events.FulfillmentPayloads;
import com.smd.fulfillmentservice.events.InvalidEventException;
import com.smd.fulfillmentservice.events.OrderConfirmed;
import com.smd.fulfillmentservice.outbox.OutboxWriter;
import com.smd.fulfillmentservice.outbox.Topics;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FulfillmentService {

    private static final Logger log = LoggerFactory.getLogger(FulfillmentService.class);

    private final FulfillmentRepository fulfillments;
    private final ProcessedEvents processedEvents;
    private final OutboxWriter outbox;
    private final ObjectMapper objectMapper;
    private final SimulationProperties simulation;
    private final Clock clock;

    public FulfillmentService(FulfillmentRepository fulfillments, ProcessedEvents processedEvents, OutboxWriter outbox,
                              ObjectMapper objectMapper, SimulationProperties simulation, Clock clock) {
        this.fulfillments = fulfillments;
        this.processedEvents = processedEvents;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.simulation = simulation;
        this.clock = clock;
    }

    /**
     * ORDER_CONFIRMED → a RECEIVED fulfillment + FULFILLMENT_RECEIVED, all in one transaction together with the
     * processed-event marker. A redelivered event is skipped (marker exists); the unique order_id is a second guard.
     */
    @Transactional
    public void onOrderConfirmed(EventEnvelope event) {
        if (event.orderId() == null || event.userId() == null || event.payload() == null) {
            throw new InvalidEventException("ORDER_CONFIRMED without orderId, userId or payload: " + event.eventId());
        }
        if (!processedEvents.markProcessed(event.eventId())) {
            log.debug("Skipping duplicate event {}", event.eventId());
            return;
        }
        if (fulfillments.findByOrderId(event.orderId()).isPresent()) {
            log.info("Order {} already has a fulfillment; ignoring event {}", event.orderId(), event.eventId());
            return;
        }
        OrderConfirmed order = parse(event);
        Fulfillment fulfillment = fulfillments.save(Fulfillment.receive(event.orderId(), event.userId(),
                simulation.warehouseCode(), order, clock.instant().plus(simulation.stepDelay())));
        outbox.append(Topics.FULFILLMENT_EVENTS, FulfillmentPayloads.FULFILLMENT_RECEIVED, event.orderId(), event.userId(),
                new FulfillmentPayloads.Received(fulfillment.getId(), fulfillment.getWarehouseCode(), order.items()));
    }

    private OrderConfirmed parse(EventEnvelope event) {
        try {
            OrderConfirmed order = objectMapper.treeToValue(event.payload(), OrderConfirmed.class);
            if (order.items() == null || order.items().isEmpty() || order.shippingAddress() == null) {
                throw new InvalidEventException("ORDER_CONFIRMED without items or address: " + event.eventId());
            }
            return order;
        } catch (JsonProcessingException e) {
            throw new InvalidEventException("Unreadable ORDER_CONFIRMED payload: " + event.eventId());
        }
    }
}
