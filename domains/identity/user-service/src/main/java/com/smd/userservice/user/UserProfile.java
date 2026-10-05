package com.smd.userservice.user;

import java.util.Optional;

/** A user together with their default shipping address, if they have one. */
public record UserProfile(User user, Optional<Address> defaultAddress) {
}
