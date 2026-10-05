package com.smd.fulfillmentservice.events;

/** The record can never be processed: not retried, sent straight to the DLT. */
public class InvalidEventException extends RuntimeException {

    public InvalidEventException(String message) {
        super(message);
    }
}
