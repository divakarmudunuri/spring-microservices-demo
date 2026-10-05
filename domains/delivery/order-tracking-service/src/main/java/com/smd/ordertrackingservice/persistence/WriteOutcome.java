package com.smd.ordertrackingservice.persistence;

/** What happened when an event was written; also the {@code outcome} tag of {@code tracking.dynamodb.write}. */
public enum WriteOutcome {
    /** new timeline entry, status moved forward */
    WRITTEN,
    /** the event was already recorded (redelivery): nothing changed */
    DUPLICATE,
    /** recorded in the timeline, but older than the current status, which stays as it is */
    STALE
}
