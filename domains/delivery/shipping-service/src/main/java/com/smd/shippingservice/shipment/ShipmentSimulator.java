package com.smd.shippingservice.shipment;

import com.smd.shippingservice.events.ShippingPayloads;
import com.smd.shippingservice.outbox.OutboxWriter;
import com.smd.shippingservice.outbox.Topics;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Stands in for a carrier: advances due shipments PICKED_UP → IN_TRANSIT → OUT_FOR_DELIVERY → DELIVERED, one
 * step every {@code demo.simulation.step-delay}. Every change writes an outbox event in the same transaction.
 */
@Component
public class ShipmentSimulator {

    private final JdbcClient jdbc;
    private final ShipmentRepository shipments;
    private final OutboxWriter outbox;
    private final TransactionTemplate transaction;
    private final SimulationProperties simulation;
    private final Clock clock;

    public ShipmentSimulator(JdbcClient jdbc, ShipmentRepository shipments, OutboxWriter outbox,
                             TransactionTemplate transaction, SimulationProperties simulation, Clock clock) {
        this.jdbc = jdbc;
        this.shipments = shipments;
        this.outbox = outbox;
        this.transaction = transaction;
        this.simulation = simulation;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${demo.simulation.poll-interval}")
    void scheduledTick() {
        if (simulation.enabled()) {
            advanceDue();
        }
    }

    /** Advances every shipment that is due, one step each. @return how many were advanced */
    public int advanceDue() {
        Integer advanced = transaction.execute(status -> {
            Instant now = clock.instant();
            List<UUID> due = jdbc.sql("""
                            SELECT id FROM shipments
                             WHERE status <> 'DELIVERED' AND next_step_at <= :now
                             ORDER BY next_step_at
                             LIMIT 50
                               FOR UPDATE SKIP LOCKED""")
                    .param("now", now.atOffset(ZoneOffset.UTC))   // the Postgres driver binds OffsetDateTime, not Instant
                    .query(UUID.class)
                    .list();
            due.forEach(id -> advance(shipments.findById(id).orElseThrow(), now));
            return due.size();
        });
        return advanced == null ? 0 : advanced;
    }

    private void advance(Shipment s, Instant now) {
        ShipmentStatus status = s.advance(now, now.plus(simulation.stepDelay()));
        Object payload = status == ShipmentStatus.DELIVERED
                ? new ShippingPayloads.Delivered(s.getId(), s.getTrackingNumber(), now)
                : new ShippingPayloads.Progress(s.getId(), s.getTrackingNumber());
        outbox.append(Topics.SHIPPING_EVENTS, status.eventType(), s.getOrderId(), s.getUserId(), payload);
    }
}
