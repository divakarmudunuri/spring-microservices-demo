package com.smd.orderservice.api;

import com.smd.orderservice.order.Order;
import com.smd.orderservice.order.OrderStatus;
import com.smd.orderservice.order.RejectionReason;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One line of an order list (no items: open the order for those). */
public record OrderSummary(UUID id, UUID userId, OrderStatus status, RejectionReason rejectionReason,
                           BigDecimal totalAmount, String currency, Instant createdAt) {

    static OrderSummary from(Order o) {
        return new OrderSummary(o.getId(), o.getUserId(), o.getStatus(), o.getRejectionReason(), o.getTotalAmount(),
                o.getCurrency(), o.getCreatedAt());
    }
}
