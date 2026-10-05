package com.smd.orderservice.payment;

import java.math.BigDecimal;

/** Number of payments and their sum, for one status. */
public record PaymentTotal(String status, long count, BigDecimal amount) {
}
