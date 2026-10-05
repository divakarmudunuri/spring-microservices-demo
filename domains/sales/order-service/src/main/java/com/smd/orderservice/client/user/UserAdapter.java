package com.smd.orderservice.client.user;

import com.smd.orderservice.client.DependencyUnavailableException;
import com.smd.orderservice.order.ShippingAddress;
import feign.FeignException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** The only way the rest of order-service talks to user-service. Maps its DTOs to domain types. */
@Component
public class UserAdapter {

    private final UserClient client;

    public UserAdapter(UserClient client) {
        this.client = client;
    }

    // TODO(phase-5): Resilience4j (@Retry, @CircuitBreaker, @Bulkhead) and an ErrorDecoder replace the catch blocks
    public Customer getCustomer(UUID userId) {
        try {
            return toCustomer(client.getUser(userId));
        } catch (FeignException.NotFound e) {
            throw new CustomerNotFoundException(userId);
        } catch (FeignException e) {
            throw new DependencyUnavailableException("user-service", e);
        }
    }

    private static Customer toCustomer(UserDto dto) {
        Optional<ShippingAddress> address = Optional.ofNullable(dto.defaultAddress())
                .map(a -> new ShippingAddress(a.fullName(), a.line1(), a.line2(), a.city(), a.state(),
                        a.postalCode(), a.country(), a.phone()));
        return new Customer(dto.id(), dto.fullName(), dto.email(), "ACTIVE".equals(dto.status()), address);
    }
}
