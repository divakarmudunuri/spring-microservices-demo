package com.smd.orderservice.composition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smd.orderservice.OrderServiceIntegrationTest;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class ParallelCallsTest extends OrderServiceIntegrationTest {

    @Autowired
    ParallelCalls parallelCalls;

    @Autowired
    ThreadPoolTaskExecutor compositionExecutor;

    @Test
    void runsOnNamedWorkerThreadsWithTheCallersMdc() {
        MDC.put(CorrelationIds.MDC_KEY, "corr-xyz");
        try {
            String seen = ParallelCalls.await(parallelCalls.submit("probe",
                    () -> Thread.currentThread().getName() + "|" + MDC.get(CorrelationIds.MDC_KEY)));

            assertThat(seen).startsWith("compose-").endsWith("|corr-xyz");
        } finally {
            MDC.remove(CorrelationIds.MDC_KEY);
        }
    }

    @Test
    void theSecurityContextTravelsToTheWorkerThread() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("caller-42", null));
        try {
            String seen = ParallelCalls.await(parallelCalls.submit("probe",
                    () -> SecurityContextHolder.getContext().getAuthentication().getName()));

            assertThat(seen).isEqualTo("caller-42");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void awaitRethrowsTheCallsOwnException() {
        assertThatThrownBy(() -> ParallelCalls.await(parallelCalls.submit("boom", () -> {
            throw new IllegalArgumentException("boom");
        }))).isInstanceOf(IllegalArgumentException.class).hasMessage("boom");
    }

    @Test
    void aCallPastTheDeadlineFailsWithATimeout() {
        ParallelCalls fast = new ParallelCalls(compositionExecutor, new CompositionProperties(1, 1, 1, Duration.ofMillis(100)));

        assertThatThrownBy(() -> ParallelCalls.await(fast.submit("slow", () -> {
            sleep(1_000);
            return "too late";
        }))).isInstanceOf(CompletionException.class).hasCauseInstanceOf(TimeoutException.class);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);   // simulates a slow remote call inside the task, not a test wait
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
