package com.smd.orderservice.checkout;

import java.math.BigDecimal;
import java.util.UUID;

/** An order line with the unit price fetched from product-service before the checkout transaction. */
public record PricedLine(UUID productId, int quantity, BigDecimal unitPrice) {

    public BigDecimal lineTotal() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}
