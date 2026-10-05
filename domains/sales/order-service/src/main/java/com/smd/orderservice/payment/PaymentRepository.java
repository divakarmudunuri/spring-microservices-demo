package com.smd.orderservice.payment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByOrderId(UUID orderId);

    /** Admin listing; both filters optional. */
    @Query("""
            SELECT p FROM Payment p
             WHERE (:status IS NULL OR p.status = :status)
               AND (:userId IS NULL OR p.userId = :userId)""")
    Page<Payment> search(@Param("status") String status, @Param("userId") UUID userId, Pageable pageable);

    /** Count and sum per status, for the same customer filter as the listing. */
    @Query("""
            SELECT new com.smd.orderservice.payment.PaymentTotal(p.status, count(p), sum(p.amount))
              FROM Payment p
             WHERE (:userId IS NULL OR p.userId = :userId)
             GROUP BY p.status
             ORDER BY p.status""")
    List<PaymentTotal> totalsByStatus(@Param("userId") UUID userId);
}
