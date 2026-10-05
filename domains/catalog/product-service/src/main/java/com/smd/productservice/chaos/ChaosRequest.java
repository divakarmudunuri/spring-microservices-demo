package com.smd.productservice.chaos;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** A field left out (null) keeps its current value. */
public record ChaosRequest(
        @Min(0) @Max(60_000) Long latencyMs,
        @DecimalMin("0.0") @DecimalMax("1.0") Double failureRate) {
}
