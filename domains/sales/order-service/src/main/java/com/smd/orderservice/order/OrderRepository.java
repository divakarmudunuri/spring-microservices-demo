package com.smd.orderservice.order;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface OrderRepository extends JpaRepository<Order, UUID>, JpaSpecificationExecutor<Order> {

    @EntityGraph(attributePaths = "items")
    Optional<Order> findByIdempotencyKey(String idempotencyKey);

    @EntityGraph(attributePaths = "items")
    Optional<Order> findWithItemsById(UUID id);

    /** "My orders", newest first (index ix_orders_user_created). Lists are shown without items. */
    Page<Order> findByUserId(UUID userId, Pageable pageable);
}
