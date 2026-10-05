package com.smd.userservice.api;

import com.smd.userservice.user.UserQueryService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserQueryService userQueries;

    public UserController(UserQueryService userQueries) {
        this.userQueries = userQueries;
    }

    /**
     * Called by other services through Feign: checkout (status + default address) and
     * the order-details aggregator (name + email).
     * TODO(phase-11): allow only the owner or an ADMIN (relayed JWT).
     */
    @GetMapping("/{id}")
    public UserResponse getUser(@PathVariable UUID id) {
        return UserResponse.from(userQueries.getProfile(id));
    }
}
