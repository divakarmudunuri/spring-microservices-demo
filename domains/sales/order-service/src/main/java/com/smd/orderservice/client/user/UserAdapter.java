package com.smd.orderservice.client.user;

import com.smd.orderservice.client.DownstreamErrors;
import com.smd.orderservice.order.ShippingAddress;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The only way the rest of order-service talks to user-service. Applies resilience and maps DTOs to
 * domain types. The Resilience4j annotations live here, not on the Feign interface
 * ({@code spring.cloud.openfeign.circuitbreaker.enabled=false}), so each call is wrapped exactly once:
 * Retry( CircuitBreaker( Bulkhead( call ))) — the order is set in application.yml.
 */
@Component
public class UserAdapter {

    static final String RESILIENCE = "userService";

    private final UserClient client;

    public UserAdapter(UserClient client) {
        this.client = client;
    }

    /**
     * @throws CustomerNotFoundException the user doesn't exist (404)
     * @throws com.smd.orderservice.client.DependencyUnavailableException anything else went wrong
     */
    @Retry(name = RESILIENCE, fallbackMethod = "translateFailure")
    @CircuitBreaker(name = RESILIENCE)
    @Bulkhead(name = RESILIENCE)
    public Customer getCustomer(UUID userId) {
        return toCustomer(client.getUser(userId));
    }

    // called by Resilience4j after the last attempt failed; no fallback value, see DownstreamErrors.translate
    private Customer translateFailure(UUID userId, Throwable failure) {
        throw DownstreamErrors.translate("user-service", failure, CustomerNotFoundException.class);
    }

    private static Customer toCustomer(UserDto dto) {
        Optional<ShippingAddress> address = Optional.ofNullable(dto.defaultAddress())
                .map(a -> new ShippingAddress(a.fullName(), a.line1(), a.line2(), a.city(), a.state(),
                        a.postalCode(), a.country(), a.phone()));
        return new Customer(dto.id(), dto.fullName(), dto.email(), "ACTIVE".equals(dto.status()), address);
    }
}
