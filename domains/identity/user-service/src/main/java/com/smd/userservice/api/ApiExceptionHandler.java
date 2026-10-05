package com.smd.userservice.api;

import com.smd.userservice.chaos.ChaosFailureException;
import com.smd.userservice.user.UserNotFoundException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Business errors as RFC 7807 ProblemDetail with a stable {@code type} per error. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(UserNotFoundException.class)
    ProblemDetail userNotFound(UserNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setType(URI.create("/problems/user-not-found"));
        problem.setTitle("User not found");
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
