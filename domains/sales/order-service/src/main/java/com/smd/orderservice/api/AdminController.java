package com.smd.orderservice.api;

import com.smd.orderservice.details.OrderDetailsService;
import com.smd.orderservice.inventory.InventoryAdminService;
import com.smd.orderservice.inventory.InventoryView;
import com.smd.orderservice.order.OrderQueryService;
import com.smd.orderservice.order.OrderStatus;
import com.smd.orderservice.payment.Payment;
import com.smd.orderservice.payment.PaymentRepository;
import com.smd.orderservice.payment.PaymentTotal;
import com.smd.orderservice.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admins see every order, payment and exact stock level, and restock products. Admins don't place orders. */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final OrderQueryService orders;
    private final OrderDetailsService orderDetails;
    private final PaymentRepository payments;
    private final InventoryAdminService inventory;
    private final CurrentUser currentUser;

    public AdminController(OrderQueryService orders, OrderDetailsService orderDetails, PaymentRepository payments,
                           InventoryAdminService inventory, CurrentUser currentUser) {
        this.orders = orders;
        this.orderDetails = orderDetails;
        this.payments = payments;
        this.inventory = inventory;
        this.currentUser = currentUser;
    }

    /**
     * All orders, newest first; every filter optional ({@code from} inclusive, {@code to} exclusive, ISO-8601).
     * "Track all orders" = this list (Postgres, indexed) + one order's timeline from order-tracking-service.
     */
    @GetMapping("/orders")
    public PageResponse<OrderSummary> orders(
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id")));
        return PageResponse.from(orders.search(status, userId, from, to, pageable), OrderSummary::from);
    }

    @GetMapping("/orders/{id}")
    public OrderResponse order(@PathVariable UUID id) {
        return OrderResponse.from(orders.getAnyOrder(id));
    }

    /** The details aggregator for any order; the admin's token is relayed to the four services. */
    @GetMapping("/orders/{id}/details")
    public OrderDetailsResponse orderDetails(@PathVariable UUID id) {
        return OrderDetailsResponse.from(orderDetails.getAnyDetails(id));
    }

    /** All payments, newest first, with count and sum per status (for the same customer filter). */
    @GetMapping("/payments")
    @Transactional(readOnly = true)
    public PaymentsResponse payments(@RequestParam(required = false) String status,
                                     @RequestParam(required = false) UUID userId,
                                     @RequestParam(defaultValue = "0") @Min(0) int page,
                                     @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id")));
        var found = payments.search(status, userId, pageable);
        return new PaymentsResponse(PageResponse.from(found, PaymentResponse::from), payments.totalsByStatus(userId));
    }

    /** Exact stock per product (names from product-service, in parallel). */
    @GetMapping("/inventory")
    public InventoryView inventory() {
        return inventory.list();
    }

    /** Adds stock; recorded with the admin's id and note, and published as INVENTORY_CHANGED. */
    @PostMapping("/inventory/{productId}/restock")
    public RestockResponse restock(@PathVariable UUID productId, @Valid @RequestBody RestockRequest request) {
        int onHand = inventory.restock(productId, request.quantity(), currentUser.id(), request.note());
        return new RestockResponse(productId, onHand);
    }

    public record RestockRequest(@Min(1) @Max(10_000) int quantity, @Size(max = 500) String note) {
    }

    public record RestockResponse(UUID productId, int quantityOnHand) {
    }

    public record PaymentResponse(UUID id, UUID orderId, UUID userId, BigDecimal amount, String currency, String status,
                                  Instant createdAt, Instant refundedAt) {

        static PaymentResponse from(Payment p) {
            return new PaymentResponse(p.getId(), p.getOrderId(), p.getUserId(), p.getAmount(), p.getCurrency(),
                    p.getStatus(), p.getCreatedAt(), p.getRefundedAt());
        }
    }

    public record PaymentsResponse(PageResponse<PaymentResponse> payments, List<PaymentTotal> totalsByStatus) {
    }
}
