package com.smd.fulfillmentservice.fulfillment;

import com.smd.fulfillmentservice.events.FulfillmentPayloads;
import com.smd.fulfillmentservice.outbox.OutboxWriter;
import com.smd.fulfillmentservice.outbox.Topics;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Stands in for a warehouse: advances due fulfillments RECEIVED → PICKING → PACKED, one step every
 * {@code demo.simulation.step-delay}. When leaving RECEIVED, a share of them ({@code demo.simulation.failure-rate})
 * fail instead (FULFILLMENT_FAILED; order-service then refunds and restocks). Every change writes an outbox event
 * in the same transaction.
 */
@Component
public class FulfillmentSimulator {

    static final String FAILURE_REASON = "Simulated warehouse failure (demo.simulation.failure-rate)";

    private final JdbcClient jdbc;
    private final FulfillmentRepository fulfillments;
    private final OutboxWriter outbox;
    private final TransactionTemplate transaction;
    private final SimulationProperties simulation;
    private final Clock clock;

    public FulfillmentSimulator(JdbcClient jdbc, FulfillmentRepository fulfillments, OutboxWriter outbox,
                                TransactionTemplate transaction, SimulationProperties simulation, Clock clock) {
        this.jdbc = jdbc;
        this.fulfillments = fulfillments;
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

    /** Advances every fulfillment that is due, one step each. @return how many were advanced */
    public int advanceDue() {
        Integer advanced = transaction.execute(status -> {
            Instant now = clock.instant();
            // SKIP LOCKED: several instances can run the simulator without advancing the same row twice
            List<UUID> due = jdbc.sql("""
                            SELECT id FROM fulfillments
                             WHERE status IN ('RECEIVED', 'PICKING') AND next_step_at <= :now
                             ORDER BY next_step_at
                             LIMIT 50
                               FOR UPDATE SKIP LOCKED""")
                    .param("now", now.atOffset(ZoneOffset.UTC))   // the Postgres driver binds OffsetDateTime, not Instant
                    .query(UUID.class)
                    .list();
            due.forEach(id -> advance(fulfillments.findById(id).orElseThrow(), now));
            return due.size();
        });
        return advanced == null ? 0 : advanced;
    }

    private void advance(Fulfillment f, Instant now) {
        switch (f.getStatus()) {
            case RECEIVED -> {
                if (ThreadLocalRandom.current().nextDouble() < simulation.failureRate()) {
                    f.fail(FAILURE_REASON);
                    emit(f, FulfillmentPayloads.FULFILLMENT_FAILED, new FulfillmentPayloads.Failed(f.getId(), FAILURE_REASON));
                } else {
                    f.startPicking(now.plus(simulation.stepDelay()));
                    emit(f, FulfillmentPayloads.FULFILLMENT_PICKING, new FulfillmentPayloads.Picking(f.getId()));
                }
            }
            case PICKING -> {
                f.pack();
                emit(f, FulfillmentPayloads.FULFILLMENT_PACKED,
                        new FulfillmentPayloads.Packed(f.getId(), f.getWarehouseCode(), f.getShippingAddress()));
            }
            default -> throw new IllegalStateException("Fulfillment " + f.getId() + " is not due: " + f.getStatus());
        }
    }

    private void emit(Fulfillment f, String eventType, Object payload) {
        outbox.append(Topics.FULFILLMENT_EVENTS, eventType, f.getOrderId(), f.getUserId(), payload);
    }
}
