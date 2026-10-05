package com.smd.orderservice.checkout;

import com.smd.orderservice.events.EventTypes;
import com.smd.orderservice.events.OrderEventPayloads;
import com.smd.orderservice.order.Order;
import com.smd.orderservice.order.OrderRepository;
import com.smd.orderservice.outbox.OutboxWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Step 2 of checkout: record the order as INITIATED. This commits on its own, before any remote call
 * or stock check, so order tracking sees the order even if checkout fails later.
 */
@Service
public class OrderInitiationService {

    private final OrderRepository orders;
    private final OutboxWriter outbox;

    public OrderInitiationService(OrderRepository orders, OutboxWriter outbox) {
        this.orders = orders;
        this.outbox = outbox;
    }

    @Transactional
    public Order initiate(UUID userId, String idempotencyKey, UUID cartId, List<OrderLine> lines) {
        Map<UUID, Integer> quantities = new LinkedHashMap<>();
        lines.forEach(line -> quantities.put(line.productId(), line.quantity()));

        // saveAndFlush: a duplicate Idempotency-Key fails here, inside this method, on the unique constraint
        Order order = orders.saveAndFlush(Order.initiate(userId, idempotencyKey, cartId, quantities));
        outbox.orderEvent(EventTypes.ORDER_INITIATED, order.getId(), userId, new OrderEventPayloads.OrderInitiated(
                lines.stream().map(l -> new OrderEventPayloads.Line(l.productId(), l.quantity())).toList(), cartId));
        return order;
    }
}
