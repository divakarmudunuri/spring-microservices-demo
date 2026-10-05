package com.smd.ordertrackingservice.persistence;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Fixed-width ISO-8601 UTC with milliseconds ({@code 2026-10-04T18:50:00.123Z}). Fixed width matters:
 * timestamps are part of the sort key, and only then does string order equal time order.
 */
public final class Timestamps {

    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private Timestamps() {
    }

    public static String format(Instant instant) {
        return FORMAT.format(instant);
    }
}
