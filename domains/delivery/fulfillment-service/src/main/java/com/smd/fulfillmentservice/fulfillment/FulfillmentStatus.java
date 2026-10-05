package com.smd.fulfillmentservice.fulfillment;

/** RECEIVED → PICKING → PACKED, or FAILED from RECEIVED (data-model/04-fulfillment_shipping_db.md). */
public enum FulfillmentStatus {
    RECEIVED,
    PICKING,
    PACKED,
    FAILED
}
