package com.smd.orderservice.client.tracking;

/** order-tracking-service answered 404: it hasn't seen an event for this order yet. Not an error. */
class TrackingNotFoundException extends RuntimeException {

    TrackingNotFoundException(String message) {
        super(message);
    }
}
