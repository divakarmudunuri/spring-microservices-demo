package com.smd.userservice.chaos;

import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Runtime chaos toggles. {@code local} profile only, and never routed by the gateway. */
@RestController
@Profile("local")
@RequestMapping("/internal/chaos")
public class ChaosController {

    private final ChaosSettings settings;

    public ChaosController(ChaosSettings settings) {
        this.settings = settings;
    }

    @GetMapping
    public ChaosProperties get() {
        return settings.current();
    }

    @PostMapping
    public ChaosProperties update(@Valid @RequestBody ChaosRequest request) {
        return settings.update(request.latencyMs(), request.failureRate());
    }
}
