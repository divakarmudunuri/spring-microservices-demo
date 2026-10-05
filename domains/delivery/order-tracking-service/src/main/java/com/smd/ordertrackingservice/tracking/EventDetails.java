package com.smd.ordertrackingservice.tracking;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;

/** Picks the few payload fields worth showing in a timeline entry. Unknown fields are ignored. */
final class EventDetails {

    private EventDetails() {
    }

    static Map<String, String> from(String eventType, JsonNode payload) {
        Map<String, String> details = new LinkedHashMap<>();
        if (payload == null || !payload.isObject()) {
            return details;
        }
        switch (eventType) {
            case "ORDER_INITIATED", "INVENTORY_RESERVED" -> details.put("itemCount", Integer.toString(payload.path("items").size()));
            case "ORDER_CONFIRMED" -> {
                details.put("itemCount", Integer.toString(payload.path("items").size()));
                copy(payload, details, "totalAmount", "currency");
            }
            case "PAYMENT_CAPTURED", "PAYMENT_REFUNDED" -> copy(payload, details, "amount", "currency");
            case "ORDER_REJECTED", "ORDER_FAILED", "FULFILLMENT_FAILED" -> copy(payload, details, "reason", "detail");
            default -> copy(payload, details, "trackingNumber", "carrier", "estimatedDelivery");
        }
        return details;
    }

    private static void copy(JsonNode payload, Map<String, String> details, String... fields) {
        for (String field : fields) {
            JsonNode value = payload.get(field);
            if (value != null && !value.isNull()) {
                details.put(field, value.asText());
            }
        }
    }
}
