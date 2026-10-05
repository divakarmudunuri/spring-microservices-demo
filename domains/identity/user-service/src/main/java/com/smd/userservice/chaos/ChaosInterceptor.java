package com.smd.userservice.chaos;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Makes this service slow or flaky on demand, so callers' timeouts, retries, circuit breakers
 * and parallel calls can be demonstrated (CLAUDE.md 6.9). Applied to {@code /api/**} only.
 */
class ChaosInterceptor implements HandlerInterceptor {

    private final ChaosSettings settings;

    ChaosInterceptor(ChaosSettings settings) {
        this.settings = settings;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws InterruptedException {
        ChaosProperties chaos = settings.current();
        if (chaos.latencyMs() > 0) {
            Thread.sleep(chaos.latencyMs());   // simulated slow dependency: blocks this request thread on purpose
        }
        if (chaos.failureRate() > 0 && ThreadLocalRandom.current().nextDouble() < chaos.failureRate()) {
            throw new ChaosFailureException();
        }
        return true;
    }
}
