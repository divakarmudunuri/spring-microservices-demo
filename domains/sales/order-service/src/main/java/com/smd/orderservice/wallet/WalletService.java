package com.smd.orderservice.wallet;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** The simulated payment method: a balance per customer, with a ledger ({@code wallet_transactions}). */
@Service
public class WalletService {

    static final int RECENT = 10;

    private final WalletRepository wallets;
    private final WalletTransactionRepository transactions;
    private final TransactionTemplate transaction;

    public WalletService(WalletRepository wallets, WalletTransactionRepository transactions, TransactionTemplate transaction) {
        this.wallets = wallets;
        this.transactions = transactions;
        this.transaction = transaction;
    }

    /** Opens the wallet, creating it with balance 0 the first time. */
    @Transactional
    public WalletView open(UUID userId) {
        wallets.createIfMissing(userId);
        return view(userId);
    }

    /**
     * Adds money, once per {@code Idempotency-Key}: the ledger row and the balance change commit together, and the
     * unique (user_id, idempotency_key) makes a repeated (or concurrent) request with the same key a no-op that
     * returns the wallet as it is.
     */
    public WalletView topUp(UUID userId, BigDecimal amount, String idempotencyKey) {
        try {
            return transaction.execute(status -> {
                wallets.createIfMissing(userId);
                transactions.saveAndFlush(WalletTransaction.topUp(userId, amount, idempotencyKey));   // the guard
                wallets.credit(userId, amount);
                return view(userId);
            });
        } catch (DataIntegrityViolationException alreadyApplied) {
            return transaction.execute(status -> view(userId));
        }
    }

    private WalletView view(UUID userId) {
        return wallets.find(userId).withTransactions(wallets.recentTransactions(userId, RECENT));
    }
}
