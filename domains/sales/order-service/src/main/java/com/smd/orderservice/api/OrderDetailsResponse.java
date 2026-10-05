package com.smd.orderservice.api;

import com.smd.orderservice.client.product.ProductInfo;
import com.smd.orderservice.client.shipping.Shipment;
import com.smd.orderservice.client.tracking.Tracking;
import com.smd.orderservice.details.OrderDetails;
import com.smd.orderservice.order.OrderStatus;
import com.smd.orderservice.order.ShippingAddress;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The composed view. A section is {@code null} when it is unavailable (listed in {@code unavailableSections})
 * or when there is nothing yet (no shipment, no tracking events): {@code unavailableSections} tells them apart.
 */
public record OrderDetailsResponse(
        UUID id,
        OrderStatus status,
        BigDecimal totalAmount,
        String currency,
        Instant createdAt,
        ShippingAddress shippingAddress,
        List<Item> items,
        CustomerSection customer,
        Shipment shipment,
        Tracking tracking,
        boolean degraded,
        List<String> unavailableSections) {

    /** {@code name} and {@code description} are null when product-service was unavailable. */
    public record Item(UUID productId, String name, String description, int quantity, BigDecimal unitPrice) {
    }

    public record CustomerSection(String fullName, String email) {
    }

    static OrderDetailsResponse from(OrderDetails d) {
        var order = d.order();
        Map<UUID, ProductInfo> products = d.products().value().orElse(Map.of());
        List<Item> items = order.getItems().stream().map(i -> {
            ProductInfo p = products.get(i.getProductId());
            return new Item(i.getProductId(), p == null ? null : p.name(), p == null ? null : p.description(),
                    i.getQuantity(), i.getUnitPrice());
        }).toList();
        CustomerSection customer = d.customer().value().map(c -> new CustomerSection(c.fullName(), c.email())).orElse(null);
        return new OrderDetailsResponse(order.getId(), order.getStatus(), order.getTotalAmount(), order.getCurrency(),
                order.getCreatedAt(), order.getShippingAddress(), items, customer,
                d.shipping().value().orElse(null), d.tracking().value().orElse(null),
                d.degraded(), d.unavailableSections());
    }
}
