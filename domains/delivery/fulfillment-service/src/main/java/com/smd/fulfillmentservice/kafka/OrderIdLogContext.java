package com.smd.fulfillmentservice.kafka;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.stereotype.Component;

/**
 * Puts the order id into the MDC while a record is handled, so every log line of that work says which order it is
 * about. On the order topics the record key <em>is</em> the order id (CLAUDE.md 6.4). Spring Boot applies a
 * {@code RecordInterceptor} bean to the auto-configured listener containers. The trace id comes separately, from
 * the listener's observation (it continues the producer's trace from the record headers).
 */
@Component
class OrderIdLogContext implements RecordInterceptor<Object, Object> {

    static final String MDC_KEY = "orderId";

    @Override
    public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record,
                                                    Consumer<Object, Object> consumer) {
        if (record.key() != null) {
            MDC.put(MDC_KEY, record.key().toString());
        }
        return record;
    }

    @Override
    public void afterRecord(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        MDC.remove(MDC_KEY);
    }
}
