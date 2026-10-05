package com.smd.productservice.catalog;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductAvailabilityRepository extends JpaRepository<ProductAvailability, UUID> {
}
