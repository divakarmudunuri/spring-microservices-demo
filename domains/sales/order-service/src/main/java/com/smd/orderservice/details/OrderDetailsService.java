package com.smd.orderservice.details;

import com.smd.orderservice.client.product.ProductAdapter;
import com.smd.orderservice.client.shipping.ShippingAdapter;
import com.smd.orderservice.client.tracking.TrackingAdapter;
import com.smd.orderservice.client.user.UserAdapter;
import com.smd.orderservice.composition.ParallelCalls;
import com.smd.orderservice.order.Order;
import com.smd.orderservice.order.OrderItem;
import com.smd.orderservice.order.OrderQueryService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The API-composition endpoint (CLAUDE.md 6.2): one order, enriched from four services <b>in parallel</b>.
 * The total time is about the slowest call, not the sum. If a call fails or times out, its section is
 * marked unavailable and the rest is still returned ({@code degraded}); a partial failure is never a 500.
 *
 * <p>Unlike the checkout lookups, these calls have a fallback ("section unavailable"): a missing customer
 * name or shipment shouldn't stop anyone from seeing their order.
 */
@Service
public class OrderDetailsService {

    private static final Logger log = LoggerFactory.getLogger(OrderDetailsService.class);

    private final OrderQueryService orders;
    private final UserAdapter users;
    private final ProductAdapter products;
    private final ShippingAdapter shipping;
    private final TrackingAdapter tracking;
    private final ParallelCalls parallelCalls;
    private final MeterRegistry meters;

    public OrderDetailsService(OrderQueryService orders, UserAdapter users, ProductAdapter products,
                               ShippingAdapter shipping, TrackingAdapter tracking, ParallelCalls parallelCalls,
                               MeterRegistry meters) {
        this.orders = orders;
        this.users = users;
        this.products = products;
        this.shipping = shipping;
        this.tracking = tracking;
        this.parallelCalls = parallelCalls;
        this.meters = meters;
    }

    /** A customer's own order (404 for someone else's). The downstream calls relay the caller's JWT. */
    public OrderDetails getDetails(UUID orderId, UUID userId) {
        return compose(orders.getOwnOrder(orderId, userId));
    }

    /** Admins: any order. The downstream services accept the relayed ADMIN token for any order too. */
    public OrderDetails getAnyDetails(UUID orderId) {
        return compose(orders.getAnyOrder(orderId));
    }

    private OrderDetails compose(Order order) {
        UUID orderId = order.getId();
        long start = System.nanoTime();

        // all four start now; none waits for another
        var customer = section("customer", () -> Optional.of(users.getCustomer(order.getUserId())));
        var productInfo = section("products", () -> Optional.of(products.getProducts(
                order.getItems().stream().map(OrderItem::getProductId).toList())));
        var shipment = section("shipping", () -> shipping.findShipment(orderId));
        var timeline = section("tracking", () -> tracking.findTracking(orderId));

        OrderDetails details = new OrderDetails(order, customer.join(), productInfo.join(), shipment.join(), timeline.join());

        long totalMs = (System.nanoTime() - start) / 1_000_000;
        meters.timer("composition.duration", "degraded", Boolean.toString(details.degraded()))
                .record(Duration.ofMillis(totalMs));
        log.debug("order details {}: {} ms in total, in parallel (customer {} ms, products {} ms, shipping {} ms, "
                        + "tracking {} ms); unavailable: {}", orderId, totalMs, details.customer().elapsedMs(),
                details.products().elapsedMs(), details.shipping().elapsedMs(), details.tracking().elapsedMs(),
                details.unavailableSections());
        return details;
    }

    /** Starts one call; any failure (after retries, open circuit, deadline) becomes "section unavailable". */
    private <T> CompletableFuture<Section<T>> section(String name, Supplier<Optional<T>> call) {
        long start = System.nanoTime();
        return parallelCalls.submit(name, call)
                .handle((value, failure) -> {
                    long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                    if (failure != null) {
                        log.warn("order details: section '{}' unavailable after {} ms: {}", name, elapsedMs, failure.toString());
                        return Section.<T>unavailable(name, elapsedMs);
                    }
                    return Section.of(name, value, elapsedMs);
                });
    }
}
