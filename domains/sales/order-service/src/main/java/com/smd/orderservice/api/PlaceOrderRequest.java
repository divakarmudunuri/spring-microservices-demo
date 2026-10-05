package com.smd.orderservice.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** Only items: the customer comes from the caller's identity, never from the body. */
public record PlaceOrderRequest(@NotEmpty @Size(max = 50) List<@Valid @NotNull Item> items) {

    public record Item(@NotNull UUID productId, @Min(1) @Max(10) int quantity) {
    }
}
