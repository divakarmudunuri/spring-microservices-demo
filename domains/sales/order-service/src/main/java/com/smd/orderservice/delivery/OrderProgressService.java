package com.smd.orderservice.delivery;

import com.smd.orderservice.events.ConsumedEvent;
import com.smd.orderservice.events.EventTypes;
import com.smd.orderservice.events.InvalidEventException;
import com.smd.orderservice.events.OrderEventPayloads;
import com.smd.orderservice.order.Order;
import com.smd.orderservice.order.OrderRepository;
import com.smd.orderservice.order.OrderStatus;
import com.smd.orderservice.outbox.OutboxWriter;
import java.time.Clock;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the order's own status in step with fulfillment and shipping (docs/events.md, "Who reacts to what").
 * The status only moves forward; duplicates are skipped through {@code processed_event}.
 */
@Service
public class OrderProgressService {

    private static final Logger log = LoggerFactory.getLogger(OrderProgressService.class);

    private final OrderRepository orders;
    private final ProcessedEvents processedEvents;
    private final OutboxWriter outbox;
    private final Clock clock;

    public OrderProgressService(OrderRepository orders, ProcessedEvents processedEvents, OutboxWriter outbox,
                                Clock clock) {
        this.orders = orders;
        this.processedEvents = processedEvents;
        this.outbox = outbox;
        this.clock = clock;
    }

    /** One transaction: the processed-event marker, the status change and ORDER_DELIVERED commit together. */
    @Transactional
    public void apply(ConsumedEvent event, OrderStatus target) {
        if (event.orderId() == null || event.eventId() == null) {
            throw new InvalidEventException(event.eventType() + " without orderId or eventId");
        }
        if (!processedEvents.markProcessed(event.eventId())) {
            log.debug("Skipping duplicate event {}", event.eventId());
            return;
        }
        Optional<Order> found = orders.findById(event.orderId());
        if (found.isEmpty()) {
            log.warn("{} for unknown order {}; ignored", event.eventType(), event.orderId());
            return;
        }
        Order order = found.get();
        OrderStatus before = order.getStatus();
        if (!order.advanceTo(target)) {
            log.info("{} would move order {} from {} to {}; ignored (status only moves forward)",
                    event.eventType(), order.getId(), before, target);
            return;
        }
        log.debug("Order {}: {} → {} ({})", order.getId(), before, target, event.eventType());
        if (target == OrderStatus.DELIVERED) {
            outbox.orderEvent(EventTypes.ORDER_DELIVERED, order.getId(), order.getUserId(),
                    new OrderEventPayloads.OrderDelivered(clock.instant()));
        }
    }
}
