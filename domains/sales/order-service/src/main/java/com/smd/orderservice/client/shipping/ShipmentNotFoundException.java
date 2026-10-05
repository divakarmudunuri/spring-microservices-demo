package com.smd.orderservice.client.shipping;

/** shipping-service answered 404: there is no shipment (yet). Not an error for the caller. */
class ShipmentNotFoundException extends RuntimeException {

    ShipmentNotFoundException(String message) {
        super(message);
    }
}
