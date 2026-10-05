package com.smd.shippingservice.shipment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smd.shippingservice.events.EventEnvelope;
import com.smd.shippingservice.events.FulfillmentPacked;
import com.smd.shippingservice.events.InvalidEventException;
import com.smd.shippingservice.events.ShippingPayloads;
import com.smd.shippingservice.outbox.OutboxWriter;
import com.smd.shippingservice.outbox.Topics;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShippingService {

    private static final Logger log = LoggerFactory.getLogger(ShippingService.class);

    private final ShipmentRepository shipments;
    private final ProcessedEvents processedEvents;
    private final OutboxWriter outbox;
    private final ObjectMapper objectMapper;
    private final SimulationProperties simulation;
    private final Clock clock;

    public ShippingService(ShipmentRepository shipments, ProcessedEvents processedEvents, OutboxWriter outbox,
                           ObjectMapper objectMapper, SimulationProperties simulation, Clock clock) {
        this.shipments = shipments;
        this.processedEvents = processedEvents;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.simulation = simulation;
        this.clock = clock;
    }

    /**
     * FULFILLMENT_PACKED → a shipment (LABEL_CREATED, new tracking number, address copied from the event) +
     * SHIPMENT_CREATED, in one transaction with the processed-event marker. Redeliveries are skipped.
     */
    @Transactional
    public void onFulfillmentPacked(EventEnvelope event) {
        if (event.orderId() == null || event.userId() == null || event.payload() == null) {
            throw new InvalidEventException("FULFILLMENT_PACKED without orderId, userId or payload: " + event.eventId());
        }
        if (!processedEvents.markProcessed(event.eventId())) {
            log.debug("Skipping duplicate event {}", event.eventId());
            return;
        }
        if (shipments.findByOrderId(event.orderId()).isPresent()) {
            log.info("Order {} already has a shipment; ignoring event {}", event.orderId(), event.eventId());
            return;
        }
        FulfillmentPacked packed = parse(event);
        LocalDate estimated = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).plusDays(simulation.deliveryDays());
        Shipment shipment = shipments.save(Shipment.createLabel(event.orderId(), event.userId(), TrackingNumbers.next(),
                simulation.carrier(), packed.shippingAddress(), estimated, clock.instant().plus(simulation.stepDelay())));
        outbox.append(Topics.SHIPPING_EVENTS, ShippingPayloads.SHIPMENT_CREATED, event.orderId(), event.userId(),
                new ShippingPayloads.Created(shipment.getId(), shipment.getTrackingNumber(), shipment.getCarrier(), estimated));
    }

    /** Admins: every shipment (or those in one status), newest first (index ix_shipments_status_created). */
    @Transactional(readOnly = true)
    public Page<Shipment> list(ShipmentStatus status, Pageable pageable) {
        return status == null ? shipments.findAll(pageable) : shipments.findByStatus(status, pageable);
    }

    @Transactional(readOnly = true)
    public Optional<Shipment> findByTrackingNumber(String trackingNumber) {
        return shipments.findByTrackingNumber(trackingNumber);
    }

    @Transactional(readOnly = true)
    public Optional<Shipment> findByOrder(UUID orderId) {
        return shipments.findByOrderId(orderId);
    }

    private FulfillmentPacked parse(EventEnvelope event) {
        try {
            FulfillmentPacked packed = objectMapper.treeToValue(event.payload(), FulfillmentPacked.class);
            if (packed.shippingAddress() == null) {
                throw new InvalidEventException("FULFILLMENT_PACKED without an address: " + event.eventId());
            }
            return packed;
        } catch (JsonProcessingException e) {
            throw new InvalidEventException("Unreadable FULFILLMENT_PACKED payload: " + event.eventId());
        }
    }
}
