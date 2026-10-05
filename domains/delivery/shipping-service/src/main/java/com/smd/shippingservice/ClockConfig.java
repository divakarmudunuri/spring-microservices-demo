package com.smd.shippingservice;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ClockConfig {

    /** Injected wherever "now" is needed, so the simulator and tests can control time. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
