package com.smd.orderservice.delivery;

import com.smd.orderservice.order.OrderStatus;
import java.util.UUID;

/** Delivery can only be acknowledged once the order is DELIVERED. → 409 */
public class NotDeliveredException extends RuntimeException {

    public NotDeliveredException(UUID orderId, OrderStatus status) {
        super("Order " + orderId + " is " + status + "; it can be acknowledged once it is DELIVERED");
    }
}
