package com.smd.orderservice.events;

import com.smd.orderservice.order.ShippingAddress;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Payloads of the events this service publishes. The shapes are the contract in docs/events.md. */
public final class OrderEventPayloads {

    private OrderEventPayloads() {
    }

    public record Line(UUID productId, int quantity) {
    }

    public record PricedLine(UUID productId, int quantity, BigDecimal unitPrice) {
    }

    public record OrderInitiated(List<Line> items, UUID cartId) {
    }

    public record InventoryReserved(List<Line> items) {
    }

    public record PaymentCaptured(UUID paymentId, BigDecimal amount, String currency) {
    }

    /** Carries everything downstream services need, so fulfillment and shipping never call back. */
    public record OrderConfirmed(List<PricedLine> items, BigDecimal totalAmount, String currency,
                                 ShippingAddress shippingAddress, UUID cartId) {
    }

    /** For ORDER_REJECTED and ORDER_FAILED. */
    public record OrderRejected(String reason, String detail) {
    }

    public record OrderDelivered(Instant deliveredAt) {
    }

    /** On {@code inventory-events}: the new exact quantity (only product-service's level is public). */
    public record InventoryChanged(UUID productId, int quantityOnHand) {
    }
}
