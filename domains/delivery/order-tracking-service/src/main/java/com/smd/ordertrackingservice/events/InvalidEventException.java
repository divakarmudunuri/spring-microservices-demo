package com.smd.ordertrackingservice.events;

/** The record can never be processed (missing required fields): not retried, sent straight to the DLT. */
public class InvalidEventException extends RuntimeException {

    public InvalidEventException(String message) {
        super(message);
    }
}
