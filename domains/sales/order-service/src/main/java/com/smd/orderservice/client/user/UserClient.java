package com.smd.orderservice.client.user;

import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Resolved through Eureka + Spring Cloud LoadBalancer: no URL here. Package-private, like its DTOs:
 * only {@link UserAdapter} can use it, so downstream DTOs never leak into the rest of the service.
 */
@FeignClient(name = "user-service")
interface UserClient {

    @GetMapping("/api/users/{id}")
    UserDto getUser(@PathVariable("id") UUID id);
}
