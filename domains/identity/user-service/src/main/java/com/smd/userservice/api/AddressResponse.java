package com.smd.userservice.api;

import com.smd.userservice.user.Address;
import java.util.UUID;

public record AddressResponse(
        UUID id,
        String fullName,
        String line1,
        String line2,
        String city,
        String state,
        String postalCode,
        String country,
        String phone) {

    static AddressResponse from(Address a) {
        return new AddressResponse(a.getId(), a.getFullName(), a.getLine1(), a.getLine2(), a.getCity(),
                a.getState(), a.getPostalCode(), a.getCountry(), a.getPhone());
    }
}
