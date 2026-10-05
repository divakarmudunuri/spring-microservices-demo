package com.smd.orderservice.delivery;

import com.smd.orderservice.events.EventTypes;
import com.smd.orderservice.events.OrderEventPayloads;
import com.smd.orderservice.order.Order;
import com.smd.orderservice.order.OrderNotFoundException;
import com.smd.orderservice.order.OrderRepository;
import com.smd.orderservice.outbox.OutboxWriter;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The customer confirms delivery: COMPLETED + {@code delivery_acknowledged_at} + DELIVERY_ACKNOWLEDGED, in one
 * transaction, so the tracking timeline ends with the customer's confirmation.
 */
@Service
public class DeliveryAcknowledgementService {

    private final OrderRepository orders;
    private final OutboxWriter outbox;
    private final Clock clock;

    public DeliveryAcknowledgementService(OrderRepository orders, OutboxWriter outbox, Clock clock) {
        this.orders = orders;
        this.outbox = outbox;
        this.clock = clock;
    }

    /**
     * Only the owner; a second call returns the same order without changing anything.
     *
     * @throws OrderNotFoundException not the caller's order (404)
     * @throws NotDeliveredException  not delivered yet (409)
     */
    @Transactional
    public Order acknowledge(UUID orderId, UUID userId) {
        Order order = orders.findWithItemsById(orderId)
                .filter(o -> o.getUserId().equals(userId))
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        boolean changed;
        try {
            changed = order.acknowledgeDelivery(clock.instant());
        } catch (IllegalStateException e) {
            throw new NotDeliveredException(orderId, order.getStatus());
        }
        if (changed) {
            outbox.orderEvent(EventTypes.DELIVERY_ACKNOWLEDGED, orderId, userId,
                    new OrderEventPayloads.DeliveryAcknowledged(order.getDeliveryAcknowledgedAt()));
        }
        return order;
    }
}
