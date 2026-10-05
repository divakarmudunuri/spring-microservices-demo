package com.smd.orderservice.checkout;

import java.util.UUID;

/** One requested line: which product and how many. */
public record OrderLine(UUID productId, int quantity) {
}
