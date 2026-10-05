package com.smd.productservice.availability;

import com.smd.productservice.catalog.AvailabilityLevel;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Turns an exact quantity (which only order-service knows) into the public level.
 *
 * @param lowStockThreshold at or below this many units a product shows LOW_STOCK (default 5)
 */
@ConfigurationProperties(prefix = "storefront")
public record AvailabilityLevels(@DefaultValue("5") int lowStockThreshold) {

    public AvailabilityLevel levelFor(int quantityOnHand) {
        if (quantityOnHand <= 0) {
            return AvailabilityLevel.OUT_OF_STOCK;
        }
        return quantityOnHand <= lowStockThreshold ? AvailabilityLevel.LOW_STOCK : AvailabilityLevel.IN_STOCK;
    }
}
