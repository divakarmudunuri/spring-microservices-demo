package com.smd.orderservice.order;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    private UUID id;

    private UUID userId;

    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    @Enumerated(EnumType.STRING)
    private RejectionReason rejectionReason;

    private BigDecimal totalAmount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 3)
    private String currency;

    @JdbcTypeCode(SqlTypes.JSON)
    private ShippingAddress shippingAddress;

    private UUID cartId;

    private String idempotencyKey;

    private Instant deliveryAcknowledgedAt;

    @Version
    private long version;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    @Column(insertable = false, updatable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("productId")
    private List<OrderItem> items = new ArrayList<>();

    protected Order() {
        // for JPA
    }

    /** A new order, before any price, stock or payment check. */
    public static Order initiate(UUID userId, String idempotencyKey, UUID cartId, Map<UUID, Integer> quantities) {
        Order order = new Order();
        order.id = UUID.randomUUID();
        order.userId = userId;
        order.status = OrderStatus.INITIATED;
        order.currency = "USD";
        order.idempotencyKey = idempotencyKey;
        order.cartId = cartId;
        quantities.forEach((productId, quantity) -> order.items.add(new OrderItem(order, productId, quantity)));
        return order;
    }

    /** Called inside the checkout transaction, after stock and wallet were updated. */
    public void confirm(Map<UUID, BigDecimal> unitPrices, BigDecimal total, ShippingAddress address) {
        requireStatus(OrderStatus.INITIATED);
        items.forEach(item -> item.setUnitPrice(unitPrices.get(item.getProductId())));
        this.totalAmount = total;
        this.shippingAddress = address;
        this.status = OrderStatus.CONFIRMED;
    }

    public void reject(RejectionReason reason) {
        requireStatus(OrderStatus.INITIATED);
        this.status = reason == RejectionReason.DEPENDENCY_UNAVAILABLE ? OrderStatus.FAILED : OrderStatus.REJECTED;
        this.rejectionReason = reason;
    }

    /**
     * Applies a delivery update (IN_FULFILLMENT, SHIPPED, DELIVERED) only if it moves the order forward.
     * Events from different topics can arrive out of order; a late one is ignored.
     *
     * @return false if the update was ignored
     */
    public boolean advanceTo(OrderStatus target) {
        if (!status.canAdvanceTo(target)) {
            return false;
        }
        status = target;
        return true;
    }

    private void requireStatus(OrderStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("Order " + id + " is " + status + ", expected " + expected);
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public RejectionReason getRejectionReason() {
        return rejectionReason;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public ShippingAddress getShippingAddress() {
        return shippingAddress;
    }

    public UUID getCartId() {
        return cartId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<OrderItem> getItems() {
        return Collections.unmodifiableList(items);
    }
}
