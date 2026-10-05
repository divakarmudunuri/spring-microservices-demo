package com.smd.orderservice.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smd.orderservice.OrderServiceIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** CLAUDE.md 6.10: checkout.attempts and checkout.duration, tagged with the outcome (for the Grafana panel). */
class CheckoutMetricsTest extends OrderServiceIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    MeterRegistry meters;

    @Test
    void everyCheckoutIsCountedAndTimedByOutcome() throws Exception {
        double confirmedBefore = attempts("confirmed");
        double outOfStockBefore = attempts("out_of_stock");

        order(EARBUDS).andExpect(status().isCreated());
        order(MONITOR).andExpect(status().isConflict());   // seeded with no stock

        assertThat(attempts("confirmed")).isEqualTo(confirmedBefore + 1);
        assertThat(attempts("out_of_stock")).isEqualTo(outOfStockBefore + 1);
        assertThat(meters.get("checkout.duration").tag("outcome", "confirmed").timer().count()).isPositive();
    }

    private double attempts(String outcome) {
        var counter = meters.find("checkout.attempts").tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    private org.springframework.test.web.servlet.ResultActions order(UUID productId) throws Exception {
        return mvc.perform(post("/api/orders")
                .with(customer(CUSTOMER)).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"productId\":\"" + productId + "\",\"quantity\":1}]}"));
    }
}
