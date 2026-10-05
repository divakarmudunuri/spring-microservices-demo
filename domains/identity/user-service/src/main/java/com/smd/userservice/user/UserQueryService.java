package com.smd.userservice.user;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserQueryService {

    private final UserRepository users;
    private final AddressRepository addresses;

    public UserQueryService(UserRepository users, AddressRepository addresses) {
        this.users = users;
        this.addresses = addresses;
    }

    @Transactional(readOnly = true)
    public UserProfile getProfile(UUID userId) {
        User user = users.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));
        return new UserProfile(user, addresses.findByUserIdAndIsDefaultTrue(userId));
    }
}
