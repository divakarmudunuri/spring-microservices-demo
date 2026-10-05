package com.smd.orderservice.outbox;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Carries the trace across the outbox (CLAUDE.md 6.10), so one order can be followed in Jaeger from the HTTP request
 * through Kafka to every consumer.
 *
 * <p>The outbox breaks the call chain on purpose: the event is written in the business transaction, and the relay
 * sends it later from a scheduler thread that knows nothing about the request. So the writer stores the current
 * W3C {@code traceparent} in {@code outbox_event.trace_parent}, and the relay re-opens a span with that parent
 * around the Kafka send. The Kafka producer observation then puts the trace into the record headers, and the
 * consumers' listener observations continue it.
 *
 * <p>Work started by a {@code @Scheduled} simulator has no span of its own. Then the writer reuses the trace of the
 * latest event for the same aggregate (order), so the whole lifecycle of an order stays one trace.
 */
@Component
public class OutboxTracing {

    private static final String TRACEPARENT = "traceparent";

    private final Tracer tracer;
    private final Propagator propagator;
    private final JdbcClient jdbc;

    public OutboxTracing(Tracer tracer, Propagator propagator, JdbcClient jdbc) {
        this.tracer = tracer;
        this.propagator = propagator;
        this.jdbc = jdbc;
    }

    /** The traceparent to store with a new outbox row, or null if there is no trace to continue. */
    public String traceParentFor(UUID aggregateId) {
        Span span = tracer.currentSpan();
        if (span != null) {
            TraceContext context = span.context();
            return "00-" + context.traceId() + "-" + context.spanId() + "-"
                    + (Boolean.TRUE.equals(context.sampled()) ? "01" : "00");
        }
        return jdbc.sql("""
                        SELECT trace_parent FROM outbox_event
                         WHERE aggregate_id = :aggregateId AND trace_parent IS NOT NULL
                         ORDER BY created_at DESC LIMIT 1""")
                .param("aggregateId", aggregateId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    /** Starts the relay's span for one row, as a child of the stored traceparent; null if the row has none. */
    public Span startPublishSpan(String traceParent, String eventType) {
        if (traceParent == null) {
            return null;
        }
        return propagator.extract(Map.of(TRACEPARENT, traceParent), Map::get)
                .name("outbox publish " + eventType)
                .tag("outbox.event_type", eventType)
                .start();
    }

    public Tracer.SpanInScope inScope(Span span) {
        return span == null ? null : tracer.withSpan(span);
    }
}
