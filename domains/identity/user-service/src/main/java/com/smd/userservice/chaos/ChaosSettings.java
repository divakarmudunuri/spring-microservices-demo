package com.smd.userservice.chaos;

import java.util.concurrent.atomic.AtomicReference;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** The current chaos toggles, changeable at runtime. Exists only in the {@code local} profile. */
@Component
@Profile("local")
public class ChaosSettings {

    private final AtomicReference<ChaosProperties> current;

    public ChaosSettings(ChaosProperties initial) {
        this.current = new AtomicReference<>(initial);
    }

    public ChaosProperties current() {
        return current.get();
    }

    public ChaosProperties update(Long latencyMs, Double failureRate) {
        return current.updateAndGet(c -> new ChaosProperties(
                latencyMs != null ? latencyMs : c.latencyMs(),
                failureRate != null ? failureRate : c.failureRate()));
    }
}
