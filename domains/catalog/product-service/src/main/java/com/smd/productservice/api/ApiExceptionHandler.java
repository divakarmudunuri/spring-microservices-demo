package com.smd.productservice.api;

import com.smd.productservice.catalog.ProductNotFoundException;
import com.smd.productservice.chaos.ChaosFailureException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Business errors as RFC 7807 ProblemDetail with a stable {@code type} per error. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ProductNotFoundException.class)
    ProblemDetail productNotFound(ProductNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setType(URI.create("/problems/product-not-found"));
        problem.setTitle("Product not found");
        return problem;
    }

    @ExceptionHandler(ChaosFailureException.class)
    ProblemDetail chaosFailure(ChaosFailureException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        problem.setType(URI.create("/problems/chaos-failure"));
        problem.setTitle("Injected failure");
        return problem;
    }
}
