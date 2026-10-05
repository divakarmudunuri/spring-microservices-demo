# order_db (order-service)

The checkout bounded context. Orders, inventory, wallets and payments share this database **on purpose**, so that stock check, stock decrement, wallet debit, payment and order confirmation are one local transaction.

Schema: [`sql/01-order_db.sql`](sql/01-order_db.sql)

![order_db (order-service)](diagrams/01-order_db.svg)

```mermaid
erDiagram
    orders ||--o{ order_items : contains
    orders ||--o| payments : "paid by"
    orders ||--o{ stock_movements : "caused"
    orders ||--o{ wallet_transactions : "charged / refunded"
    inventory ||--o{ stock_movements : "audited by"
    customer_wallets ||--o{ wallet_transactions : ledger

    orders {
        UUID id PK
        UUID user_id "user_db.users.id"
        VARCHAR status "INITIATED..COMPLETED"
        VARCHAR rejection_reason "nullable"
        NUMERIC total_amount "set at checkout"
        CHAR currency
        JSONB shipping_address "snapshot"
        UUID cart_id "nullable"
        VARCHAR idempotency_key UK
        TIMESTAMPTZ delivery_acknowledged_at
        BIGINT version
    }
    order_items {
        BIGINT id PK
        UUID order_id FK
        UUID product_id "no FK, see notes"
        INT quantity "1..10"
        NUMERIC unit_price "set at checkout"
    }
    inventory {
        UUID product_id PK "= products.id"
        INT quantity_on_hand "CHECK >= 0"
        BIGINT version
    }
    stock_movements {
        BIGINT id PK
        UUID product_id FK
        INT delta "minus = sold"
        VARCHAR reason "ORDER_CONFIRMED / ORDER_CANCELLED / RESTOCK"
        UUID order_id FK "nullable"
        UUID performed_by "admin, restocks"
        VARCHAR note
    }
    customer_wallets {
        UUID user_id PK
        NUMERIC balance "CHECK >= 0"
        CHAR currency
        BIGINT version
    }
    wallet_transactions {
        BIGINT id PK
        UUID user_id FK
        VARCHAR type "TOP_UP / PAYMENT / REFUND"
        NUMERIC amount "> 0"
        UUID order_id FK "nullable"
        VARCHAR idempotency_key "top-ups"
    }
    payments {
        UUID id PK
        UUID order_id FK,UK
        UUID user_id
        NUMERIC amount
        VARCHAR status "CAPTURED / REFUNDED"
        TIMESTAMPTZ refunded_at
    }
    outbox_event {
        UUID id PK "= eventId"
        VARCHAR topic
        UUID aggregate_id "Kafka key"
        VARCHAR event_type
        JSONB payload
        VARCHAR trace_parent
        INT attempts
        TIMESTAMPTZ published_at "null = pending"
    }
    processed_event {
        UUID event_id PK
        TIMESTAMPTZ processed_at
    }
```

## Tables

| Table | Purpose | Key rules |
|---|---|---|
| `orders` | One row per order, from `INITIATED` to `COMPLETED` | `idempotency_key` unique (double-click safe); `REJECTED`/`FAILED` must have `rejection_reason` (and only they may); anything past `CONFIRMED` must have `total_amount` + `shipping_address`; `delivery_acknowledged_at` is set exactly when `COMPLETED` |
| `order_items` | Lines of an order | Unique `(order_id, product_id)`; quantity 1–10; `unit_price` filled in at checkout |
| `inventory` | **Authoritative** stock per product | `quantity_on_hand >= 0` (cannot oversell, even with a bug) |
| `stock_movements` | Audit trail of every stock change | Sales are negative and need an order; cancellations/restocks are positive; restocks need `performed_by` (the admin) and no order |
| `customer_wallets` | Simulated payment method | `balance >= 0` (cannot overdraw); created lazily with balance 0 |
| `wallet_transactions` | Wallet ledger | Top-ups need an idempotency key; payments/refunds need an order; at most one `PAYMENT` and one `REFUND` per order |
| `payments` | One payment per order | Unique `order_id`; `refunded_at` set exactly when `REFUNDED` |
| `outbox_event` | Events waiting to be published to Kafka | Partial index on unpublished rows only; `topic` is `order-events` or `inventory-events` |
| `processed_event` | De-duplicates consumed fulfillment/shipping events | |

## Order status lifecycle

```mermaid
stateDiagram-v2
    [*] --> INITIATED
    INITIATED --> CONFIRMED: checkout transaction commits
    INITIATED --> REJECTED: out of stock / insufficient funds / ...
    INITIATED --> FAILED: dependency unavailable
    CONFIRMED --> IN_FULFILLMENT: FULFILLMENT_RECEIVED
    IN_FULFILLMENT --> SHIPPED: SHIPMENT_PICKED_UP
    SHIPPED --> DELIVERED: SHIPMENT_DELIVERED
    DELIVERED --> COMPLETED: customer acknowledges delivery
    CONFIRMED --> CANCELLED: FULFILLMENT_FAILED (refund + restock)
    IN_FULFILLMENT --> CANCELLED: FULFILLMENT_FAILED (refund + restock)
    REJECTED --> [*]
    FAILED --> [*]
    CANCELLED --> [*]
    COMPLETED --> [*]
```

## The checkout transaction, as SQL

Everything below runs in **one** `@Transactional` method. Any exception rolls all of it back. `sql/tests/order_db-tests.sql` proves the rollback leaves the first item's stock untouched.

```sql
-- per item, sorted by product_id (consistent lock order → no deadlocks)
UPDATE inventory SET quantity_on_hand = quantity_on_hand - :qty, version = version + 1
 WHERE product_id = :productId AND quantity_on_hand >= :qty;      -- 0 rows → OutOfStockException
INSERT INTO stock_movements (product_id, delta, reason, order_id) VALUES (:productId, -:qty, 'ORDER_CONFIRMED', :orderId);

UPDATE customer_wallets SET balance = balance - :total, version = version + 1
 WHERE user_id = :userId AND balance >= :total;                     -- 0 rows → InsufficientFundsException
INSERT INTO wallet_transactions (user_id, type, amount, order_id) VALUES (:userId, 'PAYMENT', :total, :orderId);
INSERT INTO payments (id, order_id, user_id, amount, status) VALUES (:paymentId, :orderId, :userId, :total, 'CAPTURED');

UPDATE orders SET status = 'CONFIRMED', total_amount = :total, shipping_address = :address WHERE id = :orderId;
INSERT INTO outbox_event ...  -- INVENTORY_RESERVED, PAYMENT_CAPTURED, ORDER_CONFIRMED, INVENTORY_CHANGED x N
```

## Design notes

- **Why inventory and wallets live here:** `@Transactional` only covers one database. Stock, money and the order must share a database to be atomic.
- **No FK from `order_items.product_id` to `inventory`:** the order is saved as `INITIATED` *before* product validation, so an unknown product id must be storable. It is then rejected with `PRODUCT_NOT_FOUND`.
- **`orders.user_id` has no FK:** users live in `user_db` (another service's database).

