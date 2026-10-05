package com.smd.orderservice.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smd.orderservice.OrderServiceIntegrationTest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

class WalletTest extends OrderServiceIntegrationTest {

    static final UUID NEW_CUSTOMER = UUID.fromString("00000000-0000-4000-8000-0000000000c3");

    @Autowired
    MockMvc mvc;

    @Autowired
    WalletService wallets;

    @Test
    void theSeededCustomerSeesBalanceAndLedger() throws Exception {
        mvc.perform(get("/api/wallet").with(customer(CUSTOMER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(500.00))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.recentTransactions[0].type").value("TOP_UP"));
    }

    @Test
    void aNewCustomersWalletIsCreatedEmptyTheFirstTime() throws Exception {
        mvc.perform(get("/api/wallet").with(customer(NEW_CUSTOMER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(0));
        assertThat(count("customer_wallets")).isEqualTo(2);
    }

    @Test
    void aTopUpIsAppliedOncePerIdempotencyKey() throws Exception {
        topUp(CUSTOMER, "top-1", "100.00").andExpect(status().isOk()).andExpect(jsonPath("$.balance").value(600.00));
        topUp(CUSTOMER, "top-1", "100.00").andExpect(status().isOk()).andExpect(jsonPath("$.balance").value(600.00));
        topUp(CUSTOMER, "top-2", "50.50").andExpect(jsonPath("$.balance").value(650.50));

        assertThat(jdbc.sql("SELECT count(*) FROM wallet_transactions WHERE idempotency_key IN ('top-1','top-2')")
                .query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void concurrentTopUpsWithTheSameKeyApplyOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(5);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<WalletView>> results = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return wallets.topUp(CUSTOMER, new BigDecimal("25.00"), "same-key");
            }));
        }
        start.countDown();
        for (Future<WalletView> r : results) {
            r.get();
        }
        pool.shutdown();

        assertThat(balance(CUSTOMER)).isEqualByComparingTo("525.00");
    }

    @Test
    void invalidTopUpsAreRejected() throws Exception {
        topUp(CUSTOMER, "too-much", "1000.01").andExpect(status().isBadRequest());
        topUp(CUSTOMER, "zero", "0").andExpect(status().isBadRequest());
        topUp(CUSTOMER, "fraction", "10.001").andExpect(status().isBadRequest());
        mvc.perform(post("/api/wallet/top-ups").with(customer(CUSTOMER)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":10}")).andExpect(status().isBadRequest());     // no Idempotency-Key
        assertThat(balance(CUSTOMER)).isEqualByComparingTo("500.00");
    }

    @Test
    void eachCustomerOnlyEverSeesTheirOwnWallet() throws Exception {
        // there's no wallet id in any path: customer B asking for "the wallet" gets B's, never A's
        mvc.perform(get("/api/wallet").with(customer(NEW_CUSTOMER))).andExpect(jsonPath("$.userId").value(NEW_CUSTOMER.toString()));
        mvc.perform(get("/api/wallet").with(admin())).andExpect(status().isForbidden());
        mvc.perform(get("/api/wallet")).andExpect(status().isUnauthorized());
    }

    private ResultActions topUp(UUID who, String key, String amount) throws Exception {
        return mvc.perform(post("/api/wallet/top-ups").with(customer(who)).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":" + amount + "}"));
    }
}
