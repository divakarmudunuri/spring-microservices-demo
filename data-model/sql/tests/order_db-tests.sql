-- =============================================================================
-- order_db-tests.sql — run AFTER 01-order_db.sql + seed. Changes nothing
-- permanently (everything runs in a transaction that is rolled back).
-- Each block must raise 'PASS'-style notices; any failure aborts with an error.
--   psql -v ON_ERROR_STOP=1 -d order_db -f order_db-tests.sql
-- =============================================================================
BEGIN;

CREATE OR REPLACE FUNCTION pg_temp.expect_fail(label text, stmt text) RETURNS void AS $$
BEGIN
    BEGIN
        EXECUTE stmt;
    EXCEPTION WHEN check_violation OR unique_violation OR foreign_key_violation OR not_null_violation THEN
        RAISE NOTICE 'PASS  %', label;
        RETURN;
    END;
    RAISE EXCEPTION 'FAIL  % (statement succeeded but should have been rejected)', label;
END;
$$ LANGUAGE plpgsql;

-- ---- last-line-of-defense constraints --------------------------------------
SELECT pg_temp.expect_fail('stock can never go negative',
  $q$UPDATE inventory SET quantity_on_hand = -1 WHERE product_id = '20000000-0000-4000-8000-000000000003'$q$);
SELECT pg_temp.expect_fail('wallet can never go negative',
  $q$UPDATE customer_wallets SET balance = -0.01 WHERE user_id = '00000000-0000-4000-8000-0000000000c1'$q$);
SELECT pg_temp.expect_fail('unknown order status rejected',
  $q$INSERT INTO orders (id, user_id, status, idempotency_key) VALUES (gen_random_uuid(), gen_random_uuid(), 'SHIPPPED', 'k-bad-status')$q$);
SELECT pg_temp.expect_fail('REJECTED needs a reason',
  $q$INSERT INTO orders (id, user_id, status, idempotency_key) VALUES (gen_random_uuid(), gen_random_uuid(), 'REJECTED', 'k-no-reason')$q$);
SELECT pg_temp.expect_fail('CONFIRMED needs total + address',
  $q$INSERT INTO orders (id, user_id, status, idempotency_key) VALUES (gen_random_uuid(), gen_random_uuid(), 'CONFIRMED', 'k-no-total')$q$);
SELECT pg_temp.expect_fail('restock must name the admin',
  $q$INSERT INTO stock_movements (product_id, delta, reason) VALUES ('20000000-0000-4000-8000-000000000001', 5, 'RESTOCK')$q$);
SELECT pg_temp.expect_fail('a sale must reduce stock',
  $q$INSERT INTO stock_movements (product_id, delta, reason, order_id) VALUES ('20000000-0000-4000-8000-000000000001', 5, 'ORDER_CONFIRMED', gen_random_uuid())$q$);

-- ---- the checkout transaction, in SQL ---------------------------------------
-- Order: 1 x mechanical-keyboard (stock 1) + 1 x monitor (stock 0).
-- Item 1 succeeds, item 2 affects 0 rows -> the application throws -> ROLLBACK.
-- Simulated here with a savepoint: after rollback, item 1's stock is untouched.
INSERT INTO orders (id, user_id, status, idempotency_key)
VALUES ('40000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-0000000000c1', 'INITIATED', 'test-checkout-1');

SAVEPOINT checkout;
DO $$
DECLARE n int;
BEGIN
    UPDATE inventory SET quantity_on_hand = quantity_on_hand - 1, version = version + 1
     WHERE product_id = '20000000-0000-4000-8000-000000000003' AND quantity_on_hand >= 1;   -- keyboard
    GET DIAGNOSTICS n = ROW_COUNT;
    IF n <> 1 THEN RAISE EXCEPTION 'expected keyboard decrement to succeed'; END IF;

    UPDATE inventory SET quantity_on_hand = quantity_on_hand - 1, version = version + 1
     WHERE product_id = '20000000-0000-4000-8000-000000000004' AND quantity_on_hand >= 1;   -- monitor
    GET DIAGNOSTICS n = ROW_COUNT;
    IF n <> 0 THEN RAISE EXCEPTION 'expected monitor decrement to affect 0 rows'; END IF;
    RAISE NOTICE 'PASS  conditional update reports out-of-stock as 0 rows';
END $$;
ROLLBACK TO SAVEPOINT checkout;   -- what @Transactional does when OutOfStockException is thrown

DO $$
BEGIN
    IF (SELECT quantity_on_hand FROM inventory WHERE product_id = '20000000-0000-4000-8000-000000000003') <> 1 THEN
        RAISE EXCEPTION 'FAIL  keyboard stock changed after rollback';
    END IF;
    RAISE NOTICE 'PASS  rollback leaves item 1 stock unchanged';
END $$;

-- ---- a successful checkout satisfies every constraint ----------------------
UPDATE inventory SET quantity_on_hand = quantity_on_hand - 2 WHERE product_id = '20000000-0000-4000-8000-000000000001' AND quantity_on_hand >= 2;
INSERT INTO stock_movements (product_id, delta, reason, order_id) VALUES ('20000000-0000-4000-8000-000000000001', -2, 'ORDER_CONFIRMED', '40000000-0000-4000-8000-000000000001');
INSERT INTO order_items (order_id, product_id, quantity, unit_price) VALUES ('40000000-0000-4000-8000-000000000001', '20000000-0000-4000-8000-000000000001', 2, 79.99);
UPDATE customer_wallets SET balance = balance - 159.98 WHERE user_id = '00000000-0000-4000-8000-0000000000c1' AND balance >= 159.98;
INSERT INTO wallet_transactions (user_id, type, amount, order_id) VALUES ('00000000-0000-4000-8000-0000000000c1', 'PAYMENT', 159.98, '40000000-0000-4000-8000-000000000001');
INSERT INTO payments (id, order_id, user_id, amount, status) VALUES (gen_random_uuid(), '40000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-0000000000c1', 159.98, 'CAPTURED');
UPDATE orders SET status = 'CONFIRMED', total_amount = 159.98, shipping_address = '{"city":"Detroit"}' WHERE id = '40000000-0000-4000-8000-000000000001';
DO $$ BEGIN RAISE NOTICE 'PASS  happy-path checkout rows satisfy all constraints'; END $$;

SELECT pg_temp.expect_fail('only one payment per order',
  $q$INSERT INTO payments (id, order_id, user_id, amount, status) VALUES (gen_random_uuid(), '40000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-0000000000c1', 1, 'CAPTURED')$q$);
SELECT pg_temp.expect_fail('only one wallet PAYMENT per order',
  $q$INSERT INTO wallet_transactions (user_id, type, amount, order_id) VALUES ('00000000-0000-4000-8000-0000000000c1', 'PAYMENT', 1, '40000000-0000-4000-8000-000000000001')$q$);
SELECT pg_temp.expect_fail('acknowledged delivery requires COMPLETED',
  $q$UPDATE orders SET delivery_acknowledged_at = now() WHERE id = '40000000-0000-4000-8000-000000000001'$q$);

ROLLBACK;
