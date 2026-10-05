package com.smd.orderservice.order;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class OrderQueryService {

    private final OrderRepository orders;

    public OrderQueryService(OrderRepository orders) {
        this.orders = orders;
    }

    /** Someone else's order is reported as not found (404, not 403), so order ids can't be probed. */
    public Order getOwnOrder(UUID orderId, UUID userId) {
        return orders.findWithItemsById(orderId)
                .filter(order -> order.getUserId().equals(userId))
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    /** Admins: any order. */
    public Order getAnyOrder(UUID orderId) {
        return orders.findWithItemsById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    public Page<Order> listOwnOrders(UUID userId, Pageable pageable) {
        return orders.findByUserId(userId, pageable);
    }

    /** Admins: all orders, each filter optional ({@code from} inclusive, {@code to} exclusive). */
    public Page<Order> search(OrderStatus status, UUID userId, Instant from, Instant to, Pageable pageable) {
        Specification<Order> spec = (root, query, cb) -> cb.conjunction();
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (userId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("userId"), userId));
        }
        if (from != null) {
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), from));
        }
        if (to != null) {
            spec = spec.and((root, query, cb) -> cb.lessThan(root.get("createdAt"), to));
        }
        return orders.findAll(spec, pageable);
    }
}
