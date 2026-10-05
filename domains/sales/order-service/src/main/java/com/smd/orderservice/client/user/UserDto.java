package com.smd.orderservice.client.user;

import java.util.UUID;

/** user-service's response, as this service needs it. Never leaves this package. */
record UserDto(UUID id, String email, String fullName, String role, String status, AddressDto defaultAddress) {

    record AddressDto(String fullName, String line1, String line2, String city, String state,
                      String postalCode, String country, String phone) {
    }
}
