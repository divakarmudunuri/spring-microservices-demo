package com.smd.orderservice.api;

import com.smd.orderservice.checkout.OrderLine;
import com.smd.orderservice.checkout.PlaceOrderUseCase;
import com.smd.orderservice.details.OrderDetailsService;
import com.smd.orderservice.order.Order;
import com.smd.orderservice.order.OrderQueryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    /** TODO(phase-11): delete. The user id will come from the JWT ({@code sub}), never from the request. */
    static final String DEMO_USER_HEADER = "X-Demo-User-Id";

    private final PlaceOrderUseCase placeOrder;
    private final OrderQueryService orderQueries;
    private final OrderDetailsService orderDetails;

    public OrderController(PlaceOrderUseCase placeOrder, OrderQueryService orderQueries, OrderDetailsService orderDetails) {
        this.placeOrder = placeOrder;
        this.orderQueries = orderQueries;
        this.orderDetails = orderDetails;
    }

    /**
     * Checkout with explicit items: {@code 201} with the CONFIRMED order, or a ProblemDetail with the
     * order id and the rejection reason (see {@link ApiExceptionHandler}). Sending the same
     * {@code Idempotency-Key} again returns the same result without placing a second order.
     */
    @PostMapping
    public ResponseEntity<OrderResponse> placeOrder(
            @RequestHeader(DEMO_USER_HEADER) UUID userId,   // TODO(phase-11): from the JWT
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 100) String idempotencyKey,
            @Valid @RequestBody PlaceOrderRequest request) {
        var lines = request.items().stream().map(i -> new OrderLine(i.productId(), i.quantity())).toList();
        Order order = placeOrder.placeOrder(userId, idempotencyKey, null, lines);
        return ResponseEntity.created(URI.create("/api/orders/" + order.getId())).body(OrderResponse.from(order));
    }

    @GetMapping("/{id}")
    public OrderResponse getOrder(
            @RequestHeader(DEMO_USER_HEADER) UUID userId,   // TODO(phase-11): from the JWT
            @PathVariable UUID id) {
        return OrderResponse.from(orderQueries.getOwnOrder(id, userId));
    }

    /**
     * The aggregator: the order plus customer, product names, shipment and tracking, fetched in parallel.
     * Always 200 for an order you own, even if some sections are unavailable ({@code degraded: true}).
     */
    @GetMapping("/{id}/details")
    public OrderDetailsResponse getOrderDetails(
            @RequestHeader(DEMO_USER_HEADER) UUID userId,   // TODO(phase-11): from the JWT
            @PathVariable UUID id) {
        return OrderDetailsResponse.from(orderDetails.getDetails(id, userId));
    }
}
