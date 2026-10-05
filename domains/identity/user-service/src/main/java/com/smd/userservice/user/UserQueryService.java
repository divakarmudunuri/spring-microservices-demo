package com.smd.userservice.user;

import java.util.Optional;
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

    /** Creates or replaces the default address. A new Google customer has none until this is called. */
    @Transactional
    public UserProfile saveDefaultAddress(UUID userId, AddressData data) {
        User user = users.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));
        Address address = addresses.findByUserIdAndIsDefaultTrue(userId).orElseGet(() -> Address.newDefault(userId));
        address.update(data.fullName(), data.line1(), data.line2(), data.city(), data.state(), data.postalCode(),
                data.country(), data.phone());
        return new UserProfile(user, Optional.of(addresses.save(address)));
    }

    public record AddressData(String fullName, String line1, String line2, String city, String state,
                              String postalCode, String country, String phone) {
    }
}
