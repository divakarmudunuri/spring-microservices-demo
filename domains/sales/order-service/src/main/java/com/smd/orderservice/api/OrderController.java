package com.smd.orderservice.api;

import com.smd.orderservice.checkout.CheckoutFromCartUseCase;
import com.smd.orderservice.checkout.OrderLine;
import com.smd.orderservice.checkout.PlaceOrderUseCase;
import com.smd.orderservice.delivery.DeliveryAcknowledgementService;
import com.smd.orderservice.details.OrderDetailsService;
import com.smd.orderservice.order.Order;
import com.smd.orderservice.order.OrderQueryService;
import com.smd.orderservice.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The customer's own orders. The customer is always the JWT's {@code sub} ({@link CurrentUser}), never something
 * in the request; someone else's order is a 404.
 */
@RestController
@RequestMapping("/api/orders")
@PreAuthorize("hasRole('CUSTOMER')")
public class OrderController {

    private final PlaceOrderUseCase placeOrder;
    private final OrderQueryService orderQueries;
    private final OrderDetailsService orderDetails;
    private final DeliveryAcknowledgementService acknowledgement;
    private final CheckoutFromCartUseCase checkoutFromCart;
    private final CurrentUser currentUser;

    public OrderController(PlaceOrderUseCase placeOrder, OrderQueryService orderQueries, OrderDetailsService orderDetails,
                           DeliveryAcknowledgementService acknowledgement, CheckoutFromCartUseCase checkoutFromCart,
                           CurrentUser currentUser) {
        this.placeOrder = placeOrder;
        this.checkoutFromCart = checkoutFromCart;
        this.orderQueries = orderQueries;
        this.orderDetails = orderDetails;
        this.acknowledgement = acknowledgement;
        this.currentUser = currentUser;
    }

    /** "My orders", newest first. */
    @GetMapping
    public PageResponse<OrderSummary> listOrders(@RequestParam(defaultValue = "0") @Min(0) int page,
                                                 @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id")));
        return PageResponse.from(orderQueries.listOwnOrders(currentUser.id(), pageable), OrderSummary::from);
    }

    /**
     * Checkout with explicit items: {@code 201} with the CONFIRMED order, or a ProblemDetail with the
     * order id and the rejection reason (see {@link ApiExceptionHandler}). Sending the same
     * {@code Idempotency-Key} again returns the same result without placing a second order.
     */
    @PostMapping
    public ResponseEntity<OrderResponse> placeOrder(
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 100) String idempotencyKey,
            @Valid @RequestBody PlaceOrderRequest request) {
        var lines = request.items().stream().map(i -> new OrderLine(i.productId(), i.quantity())).toList();
        Order order = placeOrder.placeOrder(currentUser.id(), idempotencyKey, null, lines);
        return ResponseEntity.created(URI.create("/api/orders/" + order.getId())).body(OrderResponse.from(order));
    }

    /**
     * The storefront's checkout: the customer's cart becomes the order (empty body). Same responses as
     * {@code POST /api/orders}, plus {@code 422 EMPTY_CART}. {@code Idempotency-Key} is optional here: without one,
     * the cart's id + version is the key, so the same cart contents can only be ordered once.
     */
    @PostMapping("/checkout")
    public ResponseEntity<OrderResponse> checkout(
            @RequestHeader(value = "Idempotency-Key", required = false) @Size(max = 100) String idempotencyKey) {
        Order order = checkoutFromCart.checkout(currentUser.id(), idempotencyKey);
        return ResponseEntity.created(URI.create("/api/orders/" + order.getId())).body(OrderResponse.from(order));
    }

    @GetMapping("/{id}")
    public OrderResponse getOrder(@PathVariable UUID id) {
        return OrderResponse.from(orderQueries.getOwnOrder(id, currentUser.id()));
    }

    /**
     * The aggregator: the order plus customer, product names, shipment and tracking, fetched in parallel.
     * Always 200 for an order you own, even if some sections are unavailable ({@code degraded: true}).
     */
    @GetMapping("/{id}/details")
    public OrderDetailsResponse getOrderDetails(@PathVariable UUID id) {
        return OrderDetailsResponse.from(orderDetails.getDetails(id, currentUser.id()));
    }

    /**
     * The customer confirms delivery: COMPLETED, and the tracking timeline ends with DELIVERY_ACKNOWLEDGED.
     * {@code 409} before the order is DELIVERED; a second call returns the same order unchanged.
     */
    @PostMapping("/{id}/acknowledge-delivery")
    public OrderResponse acknowledgeDelivery(@PathVariable UUID id) {
        return OrderResponse.from(acknowledgement.acknowledge(id, currentUser.id()));
    }
}
