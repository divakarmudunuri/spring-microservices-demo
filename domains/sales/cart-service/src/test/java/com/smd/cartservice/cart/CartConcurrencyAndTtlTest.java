package com.smd.cartservice.cart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smd.cartservice.CartIntegrationTest;
import com.smd.cartservice.persistence.CartConflictException;
import com.smd.cartservice.persistence.CartItem;
import com.smd.cartservice.persistence.CartRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CartConcurrencyAndTtlTest extends CartIntegrationTest {

    @Autowired
    CartService carts;

    @Autowired
    CartRepository repository;

    @Test
    void aWriteBasedOnAStaleVersionIsRejected() {
        String cartId = carts.createGuestCart().cartId();
        CartItem first = repository.find(cartId).orElseThrow();
        CartItem second = repository.find(cartId).orElseThrow();

        repository.save(first);   // version 1 → 2

        assertThatThrownBy(() -> repository.save(second)).isInstanceOf(CartConflictException.class);
    }

    @Test
    void concurrentAddsNeverLoseAnUpdate() throws Exception {
        String cartId = carts.createGuestCart().cartId();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            results.add(pool.submit(() -> {
                start.await();
                try {
                    carts.addItem(CartOwner.guest(cartId), EARBUDS, 1);
                    return true;
                } catch (CartBusyException e) {
                    return false;   // 409 after one retry: the client may try again
                }
            }));
        }
        start.countDown();
        int succeeded = 0;
        for (Future<Boolean> r : results) {
            succeeded += r.get() ? 1 : 0;
        }
        pool.shutdown();

        // every successful add is in the cart: no update was overwritten by another
        assertThat(repository.find(cartId).orElseThrow().getItems().get(0).getQuantity()).isEqualTo(succeeded);
        assertThat(succeeded).isPositive();
    }

    @Test
    void everyWriteSetsTheTtlGuestsAWeekCustomersAMonth() {
        String guestId = carts.createGuestCart().cartId();
        String customerCartId = carts.view(CartOwner.customer(UUID.randomUUID())).cartId();
        long now = Instant.now().getEpochSecond();

        assertThat(repository.find(guestId).orElseThrow().getExpiresAt())
                .isBetween(now + Duration.ofDays(7).toSeconds() - 60, now + Duration.ofDays(7).toSeconds() + 60);
        assertThat(repository.find(customerCartId).orElseThrow().getExpiresAt())
                .isBetween(now + Duration.ofDays(30).toSeconds() - 60, now + Duration.ofDays(30).toSeconds() + 60);
    }
}
