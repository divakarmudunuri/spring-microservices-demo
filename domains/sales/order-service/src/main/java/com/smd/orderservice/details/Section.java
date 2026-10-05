package com.smd.orderservice.details;

import java.util.Optional;

/**
 * One part of the order-details view: either the downstream answer (which may itself be "nothing yet"),
 * or "unavailable" because the call failed or ran out of time.
 */
public record Section<T>(String name, Optional<T> value, boolean available, long elapsedMs) {

    static <T> Section<T> of(String name, Optional<T> value, long elapsedMs) {
        return new Section<>(name, value, true, elapsedMs);
    }

    static <T> Section<T> unavailable(String name, long elapsedMs) {
        return new Section<>(name, Optional.empty(), false, elapsedMs);
    }
}
