package com.smd.orderservice.api;

import com.smd.orderservice.security.CurrentUser;
import com.smd.orderservice.wallet.WalletService;
import com.smd.orderservice.wallet.WalletView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The caller's own wallet: there is no wallet id in any path, so there is no one else's wallet to ask for. */
@RestController
@RequestMapping("/api/wallet")
@PreAuthorize("hasRole('CUSTOMER')")
public class WalletController {

    private final WalletService wallets;
    private final CurrentUser currentUser;

    public WalletController(WalletService wallets, CurrentUser currentUser) {
        this.wallets = wallets;
        this.currentUser = currentUser;
    }

    /** Balance and the 10 latest ledger entries; the wallet is created (balance 0) the first time. */
    @GetMapping
    public WalletView wallet() {
        return wallets.open(currentUser.id());
    }

    /** Adds money once per {@code Idempotency-Key}; a repeated request returns the wallet unchanged. */
    @PostMapping("/top-ups")
    public WalletView topUp(@RequestHeader("Idempotency-Key") @NotBlank @Size(max = 100) String idempotencyKey,
                            @Valid @RequestBody TopUpRequest request) {
        return wallets.topUp(currentUser.id(), request.amount(), idempotencyKey);
    }

    public record TopUpRequest(@NotNull @DecimalMin("0.01") @DecimalMax("1000.00") @Digits(integer = 4, fraction = 2)
                               BigDecimal amount) {
    }
}
