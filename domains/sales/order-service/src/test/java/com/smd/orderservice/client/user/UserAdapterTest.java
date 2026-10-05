package com.smd.orderservice.client.user;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smd.orderservice.client.DependencyUnavailableException;
import com.smd.orderservice.client.FeignClientTest;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** CLAUDE.md 6.3: success, 404, 500 with retry, slow → timeout, circuit opening (+ 4xx and bulkhead). */
class UserAdapterTest extends FeignClientTest {

    static final String PATH = "/api/users/" + CUSTOMER;

    @Autowired
    UserAdapter users;

    @Test
    void successIsMappedToACustomer() {
        stubUser(CUSTOMER, "ACTIVE", true);

        Customer customer = users.getCustomer(CUSTOMER);

        assertThat(customer.fullName()).isEqualTo("Demo Customer");
        assertThat(customer.active()).isTrue();
        assertThat(customer.defaultAddress()).get().extracting("city").isEqualTo("Detroit");
    }

    @Test
    void notFoundIsADomainExceptionAndIsNotRetried() {
        DOWNSTREAM.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> users.getCustomer(CUSTOMER)).isInstanceOf(CustomerNotFoundException.class);
        DOWNSTREAM.verify(1, getRequestedFor(urlPathEqualTo(PATH)));
        assertThat(breaker().getMetrics().getNumberOfFailedCalls()).as("404 is not a failure").isZero();
    }

    @Test
    void serverErrorIsRetriedAndThenSucceeds() {
        DOWNSTREAM.stubFor(get(urlPathEqualTo(PATH)).inScenario("flaky").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(500)).willSetStateTo("recovered"));
        DOWNSTREAM.stubFor(get(urlPathEqualTo(PATH)).inScenario("flaky").whenScenarioStateIs("recovered")
                .willReturn(userJson()));

        assertThat(users.getCustomer(CUSTOMER).active()).isTrue();
        DOWNSTREAM.verify(2, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    void persistentServerErrorIsRetriedThreeTimesThenUnavailable() {
        DOWNSTREAM.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> users.getCustomer(CUSTOMER))
                .isInstanceOf(DependencyUnavailableException.class)
                .hasMessageContaining("user-service");
        DOWNSTREAM.verify(3, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    void otherClientErrorsAreNotRetried() {
        DOWNSTREAM.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(400)));

        assertThatThrownBy(() -> users.getCustomer(CUSTOMER)).isInstanceOf(DependencyUnavailableException.class);
        DOWNSTREAM.verify(1, getRequestedFor(urlPathEqualTo(PATH)));
        assertThat(breaker().getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void slowResponseTimesOutAndIsRetried() {
        DOWNSTREAM.stubFor(get(urlPathEqualTo(PATH)).willReturn(userJson().withFixedDelay(1_000)));   // read timeout 300 ms

        assertThatThrownBy(() -> users.getCustomer(CUSTOMER))
                .isInstanceOf(DependencyUnavailableException.class)
                .hasRootCauseInstanceOf(java.net.SocketTimeoutException.class);
        DOWNSTREAM.verify(3, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    void circuitOpensAfterRepeatedFailuresAndStopsCallingUserService() {
        DOWNSTREAM.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(500)));

        // window of 4: call 1 records 3 failed attempts, call 2's first attempt is the 4th and opens the circuit
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> users.getCustomer(CUSTOMER)).isInstanceOf(DependencyUnavailableException.class);
        }
        assertThat(breaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        int callsSoFar = DOWNSTREAM.findAll(getRequestedFor(urlPathEqualTo(PATH))).size();

        assertThatThrownBy(() -> users.getCustomer(CUSTOMER))
                .isInstanceOf(DependencyUnavailableException.class)
                .hasCauseInstanceOf(CallNotPermittedException.class);
        DOWNSTREAM.verify(callsSoFar, getRequestedFor(urlPathEqualTo(PATH)));   // fails fast: no new request
    }

    @Test
    void fullBulkheadRejectsExtraConcurrentCalls() throws Exception {
        DOWNSTREAM.stubFor(get(urlPathEqualTo(PATH)).willReturn(userJson().withFixedDelay(200)));
        ExecutorService pool = Executors.newFixedThreadPool(3);
        List<CompletableFuture<Object>> calls = new ArrayList<>();
        for (int i = 0; i < 3; i++) {   // max-concurrent-calls = 2, max-wait 0
            calls.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return users.getCustomer(CUSTOMER);
                } catch (DependencyUnavailableException e) {
                    return e.getCause();
                }
            }, pool));
        }
        List<Object> results = calls.stream().map(CompletableFuture::join).toList();
        pool.shutdown();

        assertThat(results).filteredOn(Customer.class::isInstance).hasSize(2);
        assertThat(results).filteredOn(BulkheadFullException.class::isInstance).hasSize(1);
    }

    private CircuitBreaker breaker() {
        return circuitBreakers.circuitBreaker(UserAdapter.RESILIENCE);
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder userJson() {
        return okJson("""
                {"id":"%s","fullName":"Demo Customer","status":"ACTIVE","defaultAddress":null}""".formatted(CUSTOMER));
    }
}
