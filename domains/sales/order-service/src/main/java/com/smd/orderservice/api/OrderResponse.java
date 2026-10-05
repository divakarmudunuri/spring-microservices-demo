package com.smd.orderservice.api;

import com.smd.orderservice.order.Order;
import com.smd.orderservice.order.OrderStatus;
import com.smd.orderservice.order.RejectionReason;
import com.smd.orderservice.order.ShippingAddress;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
        UUID id,
        OrderStatus status,
        RejectionReason rejectionReason,
        BigDecimal totalAmount,
        String currency,
        List<Item> items,
        ShippingAddress shippingAddress,
        UUID cartId,
        Instant deliveryAcknowledgedAt,
        Instant createdAt) {

    public record Item(UUID productId, int quantity, BigDecimal unitPrice) {
    }

    static OrderResponse from(Order order) {
        return new OrderResponse(order.getId(), order.getStatus(), order.getRejectionReason(),
                order.getTotalAmount(), order.getCurrency(),
                order.getItems().stream().map(i -> new Item(i.getProductId(), i.getQuantity(), i.getUnitPrice())).toList(),
                order.getShippingAddress(), order.getCartId(), order.getDeliveryAcknowledgedAt(), order.getCreatedAt());
    }
}
