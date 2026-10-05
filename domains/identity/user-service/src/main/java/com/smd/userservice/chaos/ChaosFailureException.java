package com.smd.userservice.chaos;

/** Thrown for a request picked by {@code demo.chaos.failure-rate}; mapped to a 500 ProblemDetail. */
public class ChaosFailureException extends RuntimeException {

    public ChaosFailureException() {
        super("Injected failure (demo.chaos.failure-rate)");
    }
}
