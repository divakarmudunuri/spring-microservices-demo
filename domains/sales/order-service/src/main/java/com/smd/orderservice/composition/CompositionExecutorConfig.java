package com.smd.orderservice.composition;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.integration.Slf4jThreadLocalAccessor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class CompositionExecutorConfig {

    static {
        // let ContextPropagatingTaskDecorator copy the correlation id (MDC) to worker threads too;
        // the trace context is already registered by Micrometer Tracing
        ContextRegistry.getInstance().registerThreadLocalAccessor(
                new Slf4jThreadLocalAccessor(CorrelationIds.MDC_KEY));
    }

    /**
     * The one executor for fanning out remote calls (checkout lookups, order-details aggregator).
     *
     * <p>Java 17 has no virtual threads, so parallel blocking Feign calls need real threads: a bounded,
     * named pool. On Java 21+ an executor of virtual threads would be the simpler alternative.
     *
     * <p>Never use {@code CompletableFuture.supplyAsync(...)} without an executor: the common ForkJoinPool
     * is shared by the whole JVM, is sized for CPU work rather than blocking I/O, and drops the trace,
     * MDC and security context.
     */
    @Bean
    public ThreadPoolTaskExecutor compositionExecutor(CompositionProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.corePoolSize());
        executor.setMaxPoolSize(properties.maxPoolSize());
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setThreadNamePrefix("compose-");
        // When pool and queue are full, the calling thread runs the task itself. That slows the caller
        // down (back-pressure) instead of dropping work or throwing.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // carries the trace context and MDC (traceId, correlationId) over to the worker thread
        executor.setTaskDecorator(new ContextPropagatingTaskDecorator());
        // TODO(phase-11): also propagate the Spring Security context (the Feign interceptor reads the JWT from it)
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }
}
