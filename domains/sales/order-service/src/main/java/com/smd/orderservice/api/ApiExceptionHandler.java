package com.smd.orderservice.api;

import com.smd.orderservice.checkout.CheckoutRejectedException;
import com.smd.orderservice.checkout.IdempotencyKeyReusedException;
import com.smd.orderservice.checkout.InvalidOrderException;
import com.smd.orderservice.checkout.OrderInProgressException;
import com.smd.orderservice.order.OrderNotFoundException;
import com.smd.orderservice.order.RejectionReason;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Errors as RFC 7807 ProblemDetail with a stable {@code type} per error. */
@RestControllerAdvice
public class ApiExceptionHandler {

    /** Every rejection carries the order id, so the client can still look the order up (and track it). */
    @ExceptionHandler(CheckoutRejectedException.class)
    ProblemDetail checkoutRejected(CheckoutRejectedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(statusFor(e.reason()), e.getMessage());
        String slug = e.reason().name().toLowerCase().replace('_', '-');
        problem.setType(URI.create("/problems/" + slug));
        problem.setTitle(titleFor(e.reason()));
        problem.setProperty("orderId", e.orderId());
        problem.setProperty("reason", e.reason());
        return problem;
    }

    @ExceptionHandler(OrderInProgressException.class)
    ProblemDetail inProgress(OrderInProgressException e) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, "/problems/order-in-progress", "Order in progress", e);
        problem.setProperty("orderId", e.orderId());
        return problem;
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    ProblemDetail keyReused(IdempotencyKeyReusedException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "/problems/idempotency-key-reused", "Idempotency-Key reused", e);
    }

    @ExceptionHandler(InvalidOrderException.class)
    ProblemDetail invalidOrder(InvalidOrderException e) {
        return problem(HttpStatus.BAD_REQUEST, "/problems/invalid-order", "Invalid order", e);
    }

    @ExceptionHandler(OrderNotFoundException.class)
    ProblemDetail orderNotFound(OrderNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "/problems/order-not-found", "Order not found", e);
    }

    static HttpStatus statusFor(RejectionReason reason) {
        return switch (reason) {
            case OUT_OF_STOCK -> HttpStatus.CONFLICT;
            case INSUFFICIENT_FUNDS -> HttpStatus.PAYMENT_REQUIRED;
            case USER_INACTIVE, PRODUCT_NOT_FOUND, NO_SHIPPING_ADDRESS, EMPTY_CART -> HttpStatus.UNPROCESSABLE_ENTITY;
            case DEPENDENCY_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }

    private static String titleFor(RejectionReason reason) {
        return switch (reason) {
            case OUT_OF_STOCK -> "Out of stock";
            case INSUFFICIENT_FUNDS -> "Insufficient funds";
            case USER_INACTIVE -> "User inactive";
            case PRODUCT_NOT_FOUND -> "Product not found";
            case NO_SHIPPING_ADDRESS -> "No shipping address";
            case EMPTY_CART -> "Empty cart";
            case DEPENDENCY_UNAVAILABLE -> "Dependency unavailable";
        };
    }

    private static ProblemDetail problem(HttpStatus status, String type, String title, Exception e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        problem.setType(URI.create(type));
        problem.setTitle(title);
        return problem;
    }
}
