package com.smd.userservice.api;

import com.smd.userservice.security.CurrentUser;
import com.smd.userservice.user.UserNotFoundException;
import com.smd.userservice.user.UserQueryService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UserController {

    private final UserQueryService userQueries;
    private final CurrentUser currentUser;

    public UserController(UserQueryService userQueries, CurrentUser currentUser) {
        this.userQueries = userQueries;
        this.currentUser = currentUser;
    }

    /** "Am I signed in?" for the storefront. */
    @GetMapping("/api/users/me")
    @PreAuthorize("hasRole('CUSTOMER')")
    public UserResponse me() {
        return UserResponse.from(userQueries.getProfile(currentUser.id()));
    }

    @PutMapping("/api/users/me/address")
    @PreAuthorize("hasRole('CUSTOMER')")
    public UserResponse saveAddress(@Valid @RequestBody AddressRequest a) {
        return UserResponse.from(userQueries.saveDefaultAddress(currentUser.id(), new UserQueryService.AddressData(
                a.fullName(), a.line1(), a.line2(), a.city(), a.state(), a.postalCode(), a.country(), a.phone())));
    }

    /** "Am I signed in?" for the admin UI. */
    @GetMapping("/api/admin/me")
    @PreAuthorize("hasRole('ADMIN')")
    public UserResponse adminMe() {
        return UserResponse.from(userQueries.getProfile(currentUser.id()));
    }

    /**
     * Called by other services through Feign with the caller's relayed token: checkout (status + default address)
     * and the order-details aggregator. The owner or an ADMIN; anyone else gets 404, not 403, so ids can't be probed.
     */
    @GetMapping("/api/users/{id}")
    public UserResponse getUser(@PathVariable UUID id) {
        if (!currentUser.isAdmin() && !currentUser.id().equals(id)) {
            throw new UserNotFoundException(id);
        }
        return UserResponse.from(userQueries.getProfile(id));
    }
}
