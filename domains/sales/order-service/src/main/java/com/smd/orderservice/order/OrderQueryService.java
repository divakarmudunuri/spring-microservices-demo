package com.smd.orderservice.order;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderQueryService {

    private final OrderRepository orders;

    public OrderQueryService(OrderRepository orders) {
        this.orders = orders;
    }

    /** Someone else's order is reported as not found (404, not 403), so order ids can't be probed. */
    @Transactional(readOnly = true)
    public Order getOwnOrder(UUID orderId, UUID userId) {
        return orders.findWithItemsById(orderId)
                .filter(order -> order.getUserId().equals(userId))
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }
}
