package com.smd.cartservice.api;

import com.smd.cartservice.cart.CartBusyException;
import com.smd.cartservice.cart.CartLimitException;
import com.smd.cartservice.cart.CartNotFoundException;
import com.smd.cartservice.cart.ProductUnavailableException;
import com.smd.cartservice.cart.UnknownProductException;
import com.smd.cartservice.client.ProductsUnavailableException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Errors as RFC 7807 ProblemDetail with a stable {@code type} per error. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(CartNotFoundException.class)
    ProblemDetail notFound(CartNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "/problems/cart-not-found", "Cart not found", e);
    }

    @ExceptionHandler(UnknownProductException.class)
    ProblemDetail unknownProduct(UnknownProductException e) {
        return problem(HttpStatus.NOT_FOUND, "/problems/product-not-found", "Product not found", e);
    }

    @ExceptionHandler(ProductUnavailableException.class)
    ProblemDetail outOfStock(ProductUnavailableException e) {
        return problem(HttpStatus.CONFLICT, "/problems/out-of-stock", "Out of stock", e);
    }

    @ExceptionHandler(CartLimitException.class)
    ProblemDetail limit(CartLimitException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "/problems/cart-limit", "Cart limit reached", e);
    }

    @ExceptionHandler(CartBusyException.class)
    ProblemDetail busy(CartBusyException e) {
        return problem(HttpStatus.CONFLICT, "/problems/cart-conflict", "Cart changed concurrently", e);
    }

    @ExceptionHandler(ProductsUnavailableException.class)
    ProblemDetail productsUnavailable(ProductsUnavailableException e) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "/problems/dependency-unavailable", "Dependency unavailable", e);
    }

    private static ProblemDetail problem(HttpStatus status, String type, String title, Exception e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        problem.setType(URI.create(type));
        problem.setTitle(title);
        return problem;
    }
}
