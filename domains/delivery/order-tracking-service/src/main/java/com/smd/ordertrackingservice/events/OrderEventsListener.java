package com.smd.ordertrackingservice.events;

import com.smd.ordertrackingservice.tracking.TrackingService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes every order-related topic with this service's own consumer group. A record is acknowledged
 * (ack-mode RECORD) only after {@link TrackingService#record} returned, i.e. after the DynamoDB write
 * succeeded or was a confirmed duplicate. Failures go to the error handler in {@link KafkaConsumerConfig}.
 */
@Component
public class OrderEventsListener {

    private final TrackingService tracking;

    public OrderEventsListener(TrackingService tracking) {
        this.tracking = tracking;
    }

    @KafkaListener(topics = "#{'${tracking.topics}'.split(',')}")
    public void onEvent(EventEnvelope event) {
        tracking.record(event);
    }
}
