package com.smd.ordertrackingservice.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.smd.ordertrackingservice.TrackingIntegrationTest;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TrackingRepositoryTest extends TrackingIntegrationTest {

    static final String USER = "00000000-0000-4000-8000-0000000000c1";

    @Autowired
    TrackingRepository repository;

    @Test
    void timelineComesBackInTimeOrderWithTheHighestStatus() {
        UUID orderId = UUID.randomUUID();
        // written out of order on purpose
        repository.record(event(orderId, "ORDER_CONFIRMED", "2026-10-04T18:50:00.300Z"), USER, 30);
        repository.record(event(orderId, "ORDER_INITIATED", "2026-10-04T18:50:00.100Z"), USER, 10);
        repository.record(event(orderId, "PAYMENT_CAPTURED", "2026-10-04T18:50:00.200Z"), USER, 25);

        OrderTimeline timeline = repository.findTimeline(orderId).orElseThrow();

        assertThat(timeline.events()).extracting(TrackingEventItem::getStatus)
                .containsExactly("ORDER_INITIATED", "PAYMENT_CAPTURED", "ORDER_CONFIRMED");
        assertThat(timeline.state().getCurrentStatus()).isEqualTo("ORDER_CONFIRMED");
        assertThat(timeline.state().getStatusRank()).isEqualTo(30);
        assertThat(timeline.state().getUserId()).isEqualTo(USER);
        assertThat(timeline.state().getLastEventAt()).isEqualTo("2026-10-04T18:50:00.300Z");
    }

    @Test
    void sameEventTwiceIsStoredOnce() {
        UUID orderId = UUID.randomUUID();
        TrackingEventItem initiated = event(orderId, "ORDER_INITIATED", "2026-10-04T18:50:00.100Z");

        assertThat(repository.record(initiated, USER, 10)).isEqualTo(WriteOutcome.WRITTEN);
        assertThat(repository.record(initiated, USER, 10)).isEqualTo(WriteOutcome.DUPLICATE);

        assertThat(repository.findTimeline(orderId).orElseThrow().events()).hasSize(1);
    }

    @Test
    void anOlderStatusAfterANewerOneIsKeptInTheTimelineButDoesNotMoveTheStatusBack() {
        UUID orderId = UUID.randomUUID();
        repository.record(event(orderId, "SHIPMENT_IN_TRANSIT", "2026-10-04T19:10:00.000Z"), USER, 70);

        WriteOutcome outcome = repository.record(event(orderId, "FULFILLMENT_PACKED", "2026-10-04T19:00:00.000Z"), USER, 50);

        assertThat(outcome).isEqualTo(WriteOutcome.STALE);
        OrderTimeline timeline = repository.findTimeline(orderId).orElseThrow();
        assertThat(timeline.events()).extracting(TrackingEventItem::getStatus)
                .containsExactly("FULFILLMENT_PACKED", "SHIPMENT_IN_TRANSIT");
        assertThat(timeline.state().getCurrentStatus()).isEqualTo("SHIPMENT_IN_TRANSIT");
    }

    @Test
    void theSameStaleEventTwiceIsStillStoredOnce() {
        UUID orderId = UUID.randomUUID();
        repository.record(event(orderId, "ORDER_CONFIRMED", "2026-10-04T18:50:00.300Z"), USER, 30);
        TrackingEventItem late = event(orderId, "ORDER_INITIATED", "2026-10-04T18:50:00.100Z");

        assertThat(repository.record(late, USER, 10)).isEqualTo(WriteOutcome.STALE);
        assertThat(repository.record(late, USER, 10)).isEqualTo(WriteOutcome.DUPLICATE);
        assertThat(repository.findTimeline(orderId).orElseThrow().events()).hasSize(2);
    }

    @Test
    void latestReadsTheStateItem() {
        UUID orderId = UUID.randomUUID();
        repository.record(event(orderId, "ORDER_REJECTED", "2026-10-04T18:50:00.100Z"), USER, 30);

        assertThat(repository.findState(orderId)).get().extracting(TrackingStateItem::getCurrentStatus).isEqualTo("ORDER_REJECTED");
        assertThat(repository.findState(UUID.randomUUID())).isEmpty();
        assertThat(repository.findTimeline(UUID.randomUUID())).isEmpty();
    }

    private static TrackingEventItem event(UUID orderId, String type, String occurredAt) {
        String eventId = UUID.nameUUIDFromBytes((orderId + type + occurredAt).getBytes()).toString();
        TrackingEventItem item = new TrackingEventItem();
        item.setPk(TrackingRepository.orderPk(orderId));
        item.setSk("EVENT#" + occurredAt + "#" + eventId);
        item.setEventId(eventId);
        item.setEventType(type);
        item.setStatus(type);
        item.setSourceService("order-service");
        item.setOccurredAt(occurredAt);
        item.setReceivedAt(Timestamps.format(Instant.now()));
        item.setDetails(Map.of());
        return item;
    }
}
