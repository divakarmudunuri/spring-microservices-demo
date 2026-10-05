package com.smd.orderservice.composition;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

/**
 * The fan-out pattern, written once: run independent remote calls at the same time on the
 * bounded {@code compositionExecutor}, each with an overall deadline.
 *
 * <pre>{@code
 * var user = parallelCalls.submit("user", () -> users.getCustomer(id));
 * var products = parallelCalls.submit("products", () -> products.getProducts(ids));
 * Customer customer = ParallelCalls.await(user);          // total time ≈ the slowest call, not the sum
 * }</pre>
 */
@Component
public class ParallelCalls {

    private static final Logger log = LoggerFactory.getLogger(ParallelCalls.class);

    private final TaskExecutor executor;
    private final CompositionProperties properties;

    public ParallelCalls(@Qualifier("compositionExecutor") TaskExecutor compositionExecutor, CompositionProperties properties) {
        this.executor = compositionExecutor;
        this.properties = properties;
    }

    /** Starts {@code call} on the composition executor. The future fails with a TimeoutException after the deadline. */
    public <T> CompletableFuture<T> submit(String name, Supplier<T> call) {
        return CompletableFuture
                .supplyAsync(() -> timed(name, call), executor)
                .orTimeout(properties.deadline().toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Waits for the result and rethrows the call's own exception (not a CompletionException wrapper),
     * so callers can catch their domain exceptions as usual.
     */
    public static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get();
        } catch (ExecutionException e) {
            throw rethrow(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompletionException(e);
        }
    }

    private static <T> T timed(String name, Supplier<T> call) {
        long start = System.nanoTime();
        try {
            return call.get();
        } finally {
            log.debug("parallel call '{}' took {} ms", name, (System.nanoTime() - start) / 1_000_000);
        }
    }

    private static RuntimeException rethrow(Throwable cause) {
        if (cause instanceof RuntimeException runtime) {
            return runtime;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        // e.g. java.util.concurrent.TimeoutException from orTimeout
        return new CompletionException(cause);
    }
}
