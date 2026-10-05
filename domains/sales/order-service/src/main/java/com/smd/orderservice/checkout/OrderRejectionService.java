package com.smd.orderservice.checkout;

import com.smd.orderservice.events.EventTypes;
import com.smd.orderservice.events.OrderEventPayloads;
import com.smd.orderservice.order.Order;
import com.smd.orderservice.order.OrderRepository;
import com.smd.orderservice.order.OrderStatus;
import com.smd.orderservice.order.RejectionReason;
import com.smd.orderservice.outbox.OutboxWriter;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Step 5 of checkout: record why an order didn't go through. A separate bean with its own transaction,
 * so it runs after the checkout transaction has rolled back and commits independently of it.
 */
@Service
public class OrderRejectionService {

    private final OrderRepository orders;
    private final OutboxWriter outbox;

    public OrderRejectionService(OrderRepository orders, OutboxWriter outbox) {
        this.orders = orders;
        this.outbox = outbox;
    }

    /** REJECTED for a business reason; FAILED (+ ORDER_FAILED) for {@link RejectionReason#DEPENDENCY_UNAVAILABLE}. */
    @Transactional
    public Order reject(UUID orderId, RejectionReason reason, String detail) {
        Order order = orders.findWithItemsById(orderId).orElseThrow();
        order.reject(reason);
        String eventType = order.getStatus() == OrderStatus.FAILED ? EventTypes.ORDER_FAILED : EventTypes.ORDER_REJECTED;
        outbox.orderEvent(eventType, orderId, order.getUserId(), new OrderEventPayloads.OrderRejected(reason.name(), detail));
        return order;
    }
}
