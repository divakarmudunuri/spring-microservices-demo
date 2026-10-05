-- =============================================================================
-- order_db seed (local profile only)  →  Flyway: order-service db/seed/V2__seed.sql
-- Inventory rows match product_db.products ids; the wallet belongs to the sample
-- customer (user_db.users id ...c1). The sample admin has no wallet.
-- Stock: mechanical-keyboard = 1 (concurrency demo), monitor-27-4k = 0 (sold out),
--        ceramic-mug-set-4 = 3 (low stock).
-- =============================================================================

INSERT INTO inventory (product_id, quantity_on_hand) VALUES
    ('20000000-0000-4000-8000-000000000001', 40),
    ('20000000-0000-4000-8000-000000000002', 100),
    ('20000000-0000-4000-8000-000000000003', 1),
    ('20000000-0000-4000-8000-000000000004', 0),
    ('20000000-0000-4000-8000-000000000005', 25),
    ('20000000-0000-4000-8000-000000000006', 60),
    ('20000000-0000-4000-8000-000000000007', 3),
    ('20000000-0000-4000-8000-000000000008', 80),
    ('20000000-0000-4000-8000-000000000009', 120),
    ('20000000-0000-4000-8000-000000000010', 35),
    ('20000000-0000-4000-8000-000000000011', 70),
    ('20000000-0000-4000-8000-000000000012', 18);

INSERT INTO customer_wallets (user_id, balance, currency) VALUES
    ('00000000-0000-4000-8000-0000000000c1', 500.00, 'USD');

-- the opening balance is recorded as a top-up so the ledger adds up
INSERT INTO wallet_transactions (user_id, type, amount, idempotency_key) VALUES
    ('00000000-0000-4000-8000-0000000000c1', 'TOP_UP', 500.00, 'seed-opening-balance');
