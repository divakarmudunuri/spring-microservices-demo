package com.smd.orderservice.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import com.smd.orderservice.OrderServiceIntegrationTest;
import com.smd.orderservice.order.OrderStatus;
import com.smd.orderservice.order.RejectionReason;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CheckoutConcurrencyTest extends OrderServiceIntegrationTest {

    @Autowired
    PlaceOrderUseCase placeOrder;

    @Test
    void tenBuyersForTheLastUnitGiveExactlyOneWinner() throws Exception {
        assertThat(stock(KEYBOARD)).isEqualTo(1);

        List<String> outcomes = runConcurrently(10, i -> "key-race-" + i);

        assertThat(outcomes).filteredOn("CONFIRMED"::equals).hasSize(1);
        assertThat(outcomes).filteredOn("OUT_OF_STOCK"::equals).hasSize(9);
        assertThat(stock(KEYBOARD)).isZero();                                  // never negative
        assertThat(count("payments")).isOne();
        assertThat(balance(CUSTOMER)).isEqualByComparingTo("371.00");          // 500.00 − 129.00, once
        assertThat(statuses()).filteredOn("CONFIRMED"::equals).hasSize(1);
        assertThat(statuses()).filteredOn("REJECTED"::equals).hasSize(9);
    }

    @Test
    void concurrentRequestsWithTheSameKeyCreateOneOrder() throws Exception {
        List<String> outcomes = runConcurrently(5, i -> "key-same");

        assertThat(count("orders")).isOne();
        assertThat(count("payments")).isLessThanOrEqualTo(1);
        // the winner is CONFIRMED; the others either see it confirmed or still in progress
        assertThat(outcomes).allMatch(o -> o.equals("CONFIRMED") || o.equals("IN_PROGRESS"));
        assertThat(outcomes).contains("CONFIRMED");
    }

    private List<String> runConcurrently(int buyers, java.util.function.IntFunction<String> keyFor) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(buyers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < buyers; i++) {
            String key = keyFor.apply(i);
            futures.add(pool.submit(() -> {
                start.await();                                                 // release everyone at once
                try {
                    OrderStatus status = placeOrder.placeOrder(CUSTOMER, key, null,
                            List.of(new OrderLine(KEYBOARD, 1))).getStatus();
                    return status.name();
                } catch (CheckoutRejectedException e) {
                    return e.reason() == RejectionReason.OUT_OF_STOCK ? "OUT_OF_STOCK" : e.reason().name();
                } catch (OrderInProgressException e) {
                    return "IN_PROGRESS";
                }
            }));
        }
        start.countDown();
        List<String> outcomes = new ArrayList<>();
        for (Future<String> f : futures) {
            outcomes.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return outcomes;
    }
}
