package com.smd.ordertrackingservice.tracking;

import com.smd.ordertrackingservice.events.EventEnvelope;
import com.smd.ordertrackingservice.events.InvalidEventException;
import com.smd.ordertrackingservice.persistence.OrderTimeline;
import com.smd.ordertrackingservice.persistence.Timestamps;
import com.smd.ordertrackingservice.persistence.TrackingEventItem;
import com.smd.ordertrackingservice.persistence.TrackingRepository;
import com.smd.ordertrackingservice.persistence.TrackingStateItem;
import com.smd.ordertrackingservice.persistence.WriteOutcome;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Turns events into timeline entries. It knows only what the events tell it: no calls to other services.
 * The whole table could be rebuilt by replaying the topics with a new consumer group from the earliest offset.
 */
@Service
public class TrackingService {

    private static final Logger log = LoggerFactory.getLogger(TrackingService.class);

    private final TrackingRepository repository;
    private final Clock clock;

    public TrackingService(TrackingRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** @return empty when the event type is unknown and the event was skipped */
    public Optional<WriteOutcome> record(EventEnvelope event) {
        validate(event);
        Optional<Integer> rank = StatusRanks.rankOf(event.eventType());
        if (rank.isEmpty()) {
            log.info("Skipping unknown event type {} ({})", event.eventType(), event.eventId());
            return Optional.empty();
        }
        String occurredAt = Timestamps.format(event.occurredAt());
        TrackingEventItem item = new TrackingEventItem();
        item.setPk(TrackingRepository.orderPk(event.orderId()));
        item.setSk("EVENT#" + occurredAt + "#" + event.eventId());   // only the event's own fields: idempotent
        item.setEventId(event.eventId().toString());
        item.setEventType(event.eventType());
        item.setStatus(event.eventType());
        item.setSourceService(event.source());
        item.setOccurredAt(occurredAt);
        item.setReceivedAt(Timestamps.format(clock.instant()));
        item.setDetails(EventDetails.from(event.eventType(), event.payload()));

        WriteOutcome outcome = repository.record(item, event.userId().toString(), rank.get());
        log.debug("{} {} for order {}: {}", event.eventType(), event.eventId(), event.orderId(), outcome);
        return Optional.of(outcome);
    }

    public Optional<OrderTimeline> timeline(UUID orderId) {
        return repository.findTimeline(orderId);
    }

    public Optional<TrackingStateItem> latest(UUID orderId) {
        return repository.findState(orderId);
    }

    private static void validate(EventEnvelope event) {
        if (event == null || event.eventId() == null || event.eventType() == null || event.orderId() == null
                || event.userId() == null || event.occurredAt() == null) {
            throw new InvalidEventException("Event is missing eventId, eventType, orderId, userId or occurredAt: " + event);
        }
    }
}
