package com.smd.userservice.user;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AddressRepository extends JpaRepository<Address, UUID> {

    Optional<Address> findByUserIdAndIsDefaultTrue(UUID userId);
}
