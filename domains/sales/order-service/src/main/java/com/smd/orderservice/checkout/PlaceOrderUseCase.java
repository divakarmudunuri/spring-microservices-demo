package com.smd.orderservice.checkout;

import com.smd.orderservice.client.DependencyUnavailableException;
import com.smd.orderservice.client.product.ProductAdapter;
import com.smd.orderservice.client.product.ProductInfo;
import com.smd.orderservice.client.user.Customer;
import com.smd.orderservice.client.user.CustomerNotFoundException;
import com.smd.orderservice.client.user.UserAdapter;
import com.smd.orderservice.composition.ParallelCalls;
import com.smd.orderservice.inventory.OutOfStockException;
import com.smd.orderservice.order.Order;
import com.smd.orderservice.order.OrderRepository;
import com.smd.orderservice.order.OrderStatus;
import com.smd.orderservice.order.RejectionReason;
import com.smd.orderservice.order.ShippingAddress;
import com.smd.orderservice.wallet.InsufficientFundsException;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Coordinates checkout (CLAUDE.md 6.1). Placing the order <em>is</em> paying for it.
 * Deliberately not {@code @Transactional}: each step below has its own transaction (or none),
 * so a rejection can be recorded after the checkout transaction rolled back.
 *
 * <ol>
 *   <li>idempotency: an order with this key already exists → return it unchanged</li>
 *   <li>{@link OrderInitiationService}: INITIATED + ORDER_INITIATED (commits on its own)</li>
 *   <li>user-service and product-service <b>in parallel</b>; no remote call happens inside a transaction</li>
 *   <li>{@link CheckoutService}: stock + wallet + payment + CONFIRMED in one transaction</li>
 *   <li>on failure, {@link OrderRejectionService}: REJECTED/FAILED + ORDER_REJECTED/ORDER_FAILED</li>
 * </ol>
 */
@Service
public class PlaceOrderUseCase {

    private static final Logger log = LoggerFactory.getLogger(PlaceOrderUseCase.class);

    private final OrderRepository orders;
    private final OrderInitiationService initiation;
    private final CheckoutService checkout;
    private final OrderRejectionService rejection;
    private final UserAdapter users;
    private final ProductAdapter products;
    private final ParallelCalls parallelCalls;

    public PlaceOrderUseCase(OrderRepository orders, OrderInitiationService initiation, CheckoutService checkout,
                             OrderRejectionService rejection, UserAdapter users, ProductAdapter products,
                             ParallelCalls parallelCalls) {
        this.orders = orders;
        this.initiation = initiation;
        this.checkout = checkout;
        this.rejection = rejection;
        this.users = users;
        this.products = products;
        this.parallelCalls = parallelCalls;
    }

    /**
     * @return the CONFIRMED order
     * @throws CheckoutRejectedException the order was recorded as REJECTED or FAILED
     */
    public Order placeOrder(UUID userId, String idempotencyKey, UUID cartId, List<OrderLine> lines) {
        requireDistinctProducts(lines);

        // 1. idempotency
        Optional<Order> existing = orders.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), userId);
        }

        // 2. initiate (own transaction)
        Order order;
        try {
            order = initiation.initiate(userId, idempotencyKey, cartId, lines);
        } catch (DataIntegrityViolationException e) {
            // a concurrent request with the same key won the race on the unique constraint
            return replay(orders.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> e), userId);
        }
        UUID orderId = order.getId();
        if (lines.isEmpty()) {
            // only possible from a cart (POST /api/orders validates its items): recorded, then rejected
            throw rejected(orderId, RejectionReason.EMPTY_CART, "The cart is empty");
        }

        // 3. pre-checkout lookups, in parallel
        List<UUID> productIds = lines.stream().map(OrderLine::productId).toList();
        long start = System.nanoTime();
        CompletableFuture<Customer> customerCall = parallelCalls.submit("user", () -> users.getCustomer(userId));
        CompletableFuture<Map<UUID, ProductInfo>> productsCall =
                parallelCalls.submit("products", () -> products.getProducts(productIds));
        Customer customer;
        Map<UUID, ProductInfo> productInfo;
        try {
            customer = ParallelCalls.await(customerCall);
            productInfo = ParallelCalls.await(productsCall);
        } catch (CustomerNotFoundException e) {
            throw rejected(orderId, RejectionReason.USER_INACTIVE, e.getMessage());
        } catch (DependencyUnavailableException e) {
            throw rejected(orderId, RejectionReason.DEPENDENCY_UNAVAILABLE, e.getMessage());
        } catch (CompletionException e) {
            // the overall deadline (orTimeout) passed before a call finished
            throw rejected(orderId, RejectionReason.DEPENDENCY_UNAVAILABLE, "lookup timed out");
        }
        log.debug("checkout lookups for order {} took {} ms in total", orderId, (System.nanoTime() - start) / 1_000_000);

        if (!customer.active()) {
            throw rejected(orderId, RejectionReason.USER_INACTIVE, "User " + userId + " is not active");
        }
        ShippingAddress address = customer.defaultAddress().orElseThrow(() ->
                rejected(orderId, RejectionReason.NO_SHIPPING_ADDRESS, "Add a default shipping address first"));
        List<UUID> missing = productIds.stream().filter(id -> !productInfo.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            throw rejected(orderId, RejectionReason.PRODUCT_NOT_FOUND, "Unknown or inactive products: " + missing);
        }

        // prices and total are computed before the transaction starts, to keep it short
        List<PricedLine> priced = lines.stream()
                .map(l -> new PricedLine(l.productId(), l.quantity(), productInfo.get(l.productId()).price()))
                .toList();
        BigDecimal total = priced.stream().map(PricedLine::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);

        // 4. the one transaction
        try {
            return checkout.checkout(orderId, priced, total, address);
        } catch (OutOfStockException e) {
            throw rejected(orderId, RejectionReason.OUT_OF_STOCK, e.getMessage());
        } catch (InsufficientFundsException e) {
            throw rejected(orderId, RejectionReason.INSUFFICIENT_FUNDS, e.getMessage());
        }
    }

    // 5. runs after the checkout transaction has rolled back
    private CheckoutRejectedException rejected(UUID orderId, RejectionReason reason, String detail) {
        rejection.reject(orderId, reason, detail);
        return new CheckoutRejectedException(orderId, reason, detail);
    }

    private static Order replay(Order order, UUID userId) {
        if (!order.getUserId().equals(userId)) {
            throw new IdempotencyKeyReusedException();
        }
        if (order.getStatus() == OrderStatus.INITIATED) {
            throw new OrderInProgressException(order.getId());
        }
        if (order.getStatus() == OrderStatus.REJECTED || order.getStatus() == OrderStatus.FAILED) {
            throw new CheckoutRejectedException(order.getId(), order.getRejectionReason(),
                    "Order was " + order.getStatus() + ": " + order.getRejectionReason());
        }
        return order;
    }

    private static void requireDistinctProducts(List<OrderLine> lines) {
        if (new HashSet<>(lines.stream().map(OrderLine::productId).toList()).size() != lines.size()) {
            throw new InvalidOrderException("Each product may appear only once; adjust its quantity instead");
        }
    }
}
