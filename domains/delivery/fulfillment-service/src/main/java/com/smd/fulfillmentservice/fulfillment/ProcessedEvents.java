package com.smd.fulfillmentservice.fulfillment;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Consumer de-duplication: an event id is recorded in the same transaction as the handler's changes. */
@Component
public class ProcessedEvents {

    private final JdbcClient jdbc;

    public ProcessedEvents(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return false if this event was already processed (a redelivery): the caller must skip it */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markProcessed(UUID eventId) {
        return jdbc.sql("INSERT INTO processed_event (event_id) VALUES (:id) ON CONFLICT DO NOTHING")
                .param("id", eventId)
                .update() == 1;
    }
}
