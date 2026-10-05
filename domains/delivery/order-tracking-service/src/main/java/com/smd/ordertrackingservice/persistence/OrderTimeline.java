package com.smd.ordertrackingservice.persistence;

import java.util.List;

/** Everything stored for one order: the STATE item and the events in time order. */
public record OrderTimeline(TrackingStateItem state, List<TrackingEventItem> events) {
}
