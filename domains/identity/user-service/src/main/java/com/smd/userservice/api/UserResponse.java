package com.smd.userservice.api;

import com.smd.userservice.user.UserProfile;
import com.smd.userservice.user.UserRole;
import com.smd.userservice.user.UserStatus;
import java.util.UUID;

/** {@code defaultAddress} is null for users without one (e.g. a brand-new Google customer). */
public record UserResponse(
        UUID id,
        String email,
        String fullName,
        UserRole role,
        UserStatus status,
        AddressResponse defaultAddress) {

    static UserResponse from(UserProfile profile) {
        var u = profile.user();
        return new UserResponse(u.getId(), u.getEmail(), u.getFullName(), u.getRole(), u.getStatus(),
                profile.defaultAddress().map(AddressResponse::from).orElse(null));
    }
}
