-- =============================================================================
-- 01-order_db.sql  (order-service)  →  Flyway: order-service V1__init.sql
-- The "checkout" bounded context. orders, inventory, wallets and payments share
-- this database ON PURPOSE so that
--     stock check + stock decrement + wallet debit + payment + order CONFIRMED
-- run in ONE local @Transactional (see CLAUDE.md 6.1).
-- =============================================================================

-- Keeps updated_at honest even for writes that bypass JPA (e.g. native UPDATEs).
CREATE OR REPLACE FUNCTION set_updated_at() RETURNS trigger AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- -----------------------------------------------------------------------------
-- orders
-- -----------------------------------------------------------------------------
CREATE TABLE orders (
    id                        UUID          PRIMARY KEY,
    user_id                   UUID          NOT NULL,          -- internal user id (user_db.users.id); no FK: other database
    status                    VARCHAR(20)   NOT NULL,
    rejection_reason          VARCHAR(30),
    total_amount              NUMERIC(12,2),                   -- set at checkout
    currency                  CHAR(3)       NOT NULL DEFAULT 'USD',
    shipping_address          JSONB,                           -- snapshot taken at checkout
    cart_id                   UUID,                            -- set when the order came from a cart
    idempotency_key           VARCHAR(100)  NOT NULL,
    delivery_acknowledged_at  TIMESTAMPTZ,
    version                   BIGINT        NOT NULL DEFAULT 0, -- JPA @Version
    created_at                TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT uq_orders_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT ck_orders_status CHECK (status IN (
        'INITIATED','CONFIRMED','REJECTED','FAILED',
        'IN_FULFILLMENT','SHIPPED','DELIVERED','COMPLETED','CANCELLED')),
    CONSTRAINT ck_orders_rejection_reason CHECK (rejection_reason IS NULL OR rejection_reason IN (
        'OUT_OF_STOCK','INSUFFICIENT_FUNDS','USER_INACTIVE','PRODUCT_NOT_FOUND',
        'EMPTY_CART','NO_SHIPPING_ADDRESS','DEPENDENCY_UNAVAILABLE')),
    -- a reason only makes sense on a rejected/failed order
    CONSTRAINT ck_orders_reason_matches_status CHECK (
        (status IN ('REJECTED','FAILED')) = (rejection_reason IS NOT NULL)),
    CONSTRAINT ck_orders_total_non_negative CHECK (total_amount IS NULL OR total_amount >= 0),
    -- once confirmed (or anything after), the money and address must be known
    CONSTRAINT ck_orders_confirmed_has_total CHECK (
        status IN ('INITIATED','REJECTED','FAILED')
        OR (total_amount IS NOT NULL AND shipping_address IS NOT NULL)),
    CONSTRAINT ck_orders_ack_only_when_completed CHECK (
        (status = 'COMPLETED') = (delivery_acknowledged_at IS NOT NULL))
);
CREATE INDEX ix_orders_user_created ON orders (user_id, created_at DESC);   -- "my orders"
CREATE INDEX ix_orders_status_created ON orders (status, created_at DESC);  -- admin listing
CREATE INDEX ix_orders_cart ON orders (cart_id) WHERE cart_id IS NOT NULL;
CREATE TRIGGER trg_orders_updated_at BEFORE UPDATE ON orders
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- -----------------------------------------------------------------------------
-- order_items
-- product_id has no FK to inventory on purpose: the order is saved as INITIATED
-- before product validation, so an unknown product must still be recordable
-- (it is then REJECTED with PRODUCT_NOT_FOUND).
-- -----------------------------------------------------------------------------
CREATE TABLE order_items (
    id          BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id    UUID          NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    product_id  UUID          NOT NULL,
    quantity    INT           NOT NULL,
    unit_price  NUMERIC(12,2),                                  -- filled in at checkout
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT uq_order_items_order_product UNIQUE (order_id, product_id),
    CONSTRAINT ck_order_items_quantity CHECK (quantity > 0 AND quantity <= 10),
    CONSTRAINT ck_order_items_unit_price CHECK (unit_price IS NULL OR unit_price >= 0)
);
CREATE INDEX ix_order_items_product ON order_items (product_id);
CREATE TRIGGER trg_order_items_updated_at BEFORE UPDATE ON order_items
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- -----------------------------------------------------------------------------
-- inventory  (authoritative stock; public stock LEVELS live in product_db)
-- -----------------------------------------------------------------------------
CREATE TABLE inventory (
    product_id        UUID         PRIMARY KEY,                -- = product_db.products.id
    quantity_on_hand  INT          NOT NULL,
    version           BIGINT       NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- Last line of defense against overselling. Keep it even though checkout
    -- uses "UPDATE ... WHERE quantity_on_hand >= :qty".
    CONSTRAINT ck_inventory_non_negative CHECK (quantity_on_hand >= 0)
);
CREATE TRIGGER trg_inventory_updated_at BEFORE UPDATE ON inventory
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- -----------------------------------------------------------------------------
-- stock_movements  (audit trail: makes rollbacks and restocks visible)
-- -----------------------------------------------------------------------------
CREATE TABLE stock_movements (
    id            BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_id    UUID          NOT NULL REFERENCES inventory (product_id),
    delta         INT           NOT NULL,                       -- negative = sold, positive = restocked/returned
    reason        VARCHAR(20)   NOT NULL,
    order_id      UUID          REFERENCES orders (id),         -- null for restocks
    performed_by  UUID,                                         -- admin user id for restocks
    note          VARCHAR(500),
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT ck_stock_movements_reason CHECK (reason IN ('ORDER_CONFIRMED','ORDER_CANCELLED','RESTOCK')),
    CONSTRAINT ck_stock_movements_delta_sign CHECK (
        (reason = 'ORDER_CONFIRMED' AND delta < 0) OR
        (reason IN ('ORDER_CANCELLED','RESTOCK') AND delta > 0)),
    CONSTRAINT ck_stock_movements_order_link CHECK (
        (reason = 'RESTOCK' AND order_id IS NULL AND performed_by IS NOT NULL) OR
        (reason <> 'RESTOCK' AND order_id IS NOT NULL))
);
CREATE INDEX ix_stock_movements_product_created ON stock_movements (product_id, created_at DESC);
CREATE INDEX ix_stock_movements_order ON stock_movements (order_id) WHERE order_id IS NOT NULL;

-- -----------------------------------------------------------------------------
-- customer_wallets  (simulated payment method; can join the local transaction)
-- Created lazily (balance 0) the first time a customer opens their wallet.
-- -----------------------------------------------------------------------------
CREATE TABLE customer_wallets (
    user_id     UUID          PRIMARY KEY,
    balance     NUMERIC(12,2) NOT NULL DEFAULT 0,
    currency    CHAR(3)       NOT NULL DEFAULT 'USD',
    version     BIGINT        NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),

    -- Last line of defense against overdrawing.
    CONSTRAINT ck_wallet_balance_non_negative CHECK (balance >= 0)
);
CREATE TRIGGER trg_customer_wallets_updated_at BEFORE UPDATE ON customer_wallets
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE wallet_transactions (
    id          BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     UUID          NOT NULL REFERENCES customer_wallets (user_id),
    type        VARCHAR(10)   NOT NULL,
    amount      NUMERIC(12,2) NOT NULL,                         -- always positive; type gives the direction
    order_id    UUID          REFERENCES orders (id),
    idempotency_key VARCHAR(100),                               -- required for TOP_UP
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT ck_wallet_tx_type CHECK (type IN ('TOP_UP','PAYMENT','REFUND')),
    CONSTRAINT ck_wallet_tx_amount CHECK (amount > 0),
    CONSTRAINT ck_wallet_tx_order_link CHECK (
        (type = 'TOP_UP' AND order_id IS NULL AND idempotency_key IS NOT NULL) OR
        (type IN ('PAYMENT','REFUND') AND order_id IS NOT NULL)),
    CONSTRAINT uq_wallet_tx_idempotency UNIQUE (user_id, idempotency_key),
    CONSTRAINT uq_wallet_tx_one_per_order_type UNIQUE (order_id, type)   -- one PAYMENT and at most one REFUND per order
);
CREATE INDEX ix_wallet_tx_user_created ON wallet_transactions (user_id, created_at DESC);

-- -----------------------------------------------------------------------------
-- payments
-- -----------------------------------------------------------------------------
CREATE TABLE payments (
    id           UUID          PRIMARY KEY,
    order_id     UUID          NOT NULL REFERENCES orders (id),
    user_id      UUID          NOT NULL,
    amount       NUMERIC(12,2) NOT NULL,
    currency     CHAR(3)       NOT NULL DEFAULT 'USD',
    status       VARCHAR(10)   NOT NULL,
    refunded_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT uq_payments_order UNIQUE (order_id),
    CONSTRAINT ck_payments_amount CHECK (amount > 0),
    CONSTRAINT ck_payments_status CHECK (status IN ('CAPTURED','REFUNDED')),
    CONSTRAINT ck_payments_refunded_at CHECK ((status = 'REFUNDED') = (refunded_at IS NOT NULL))
);
CREATE INDEX ix_payments_status_created ON payments (status, created_at DESC);   -- admin listing
CREATE INDEX ix_payments_user_created ON payments (user_id, created_at DESC);
CREATE TRIGGER trg_payments_updated_at BEFORE UPDATE ON payments
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- -----------------------------------------------------------------------------
-- outbox_event  (transactional outbox → Kafka; see CLAUDE.md 6.4)
-- -----------------------------------------------------------------------------
CREATE TABLE outbox_event (
    id            UUID          PRIMARY KEY,                    -- = eventId in the envelope
    topic         VARCHAR(100)  NOT NULL,                       -- order-events | inventory-events
    aggregate_id  UUID          NOT NULL,                       -- Kafka key: orderId or productId
    event_type    VARCHAR(50)   NOT NULL,
    payload       JSONB         NOT NULL,                       -- full envelope
    trace_parent  VARCHAR(100),                                 -- W3C traceparent, to continue the trace
    attempts      INT           NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ
);
-- the relay only ever scans unpublished rows, oldest first
CREATE INDEX ix_outbox_unpublished ON outbox_event (created_at) WHERE published_at IS NULL;

-- -----------------------------------------------------------------------------
-- processed_event  (consumer de-duplication for fulfillment/shipping events)
-- -----------------------------------------------------------------------------
CREATE TABLE processed_event (
    event_id      UUID         PRIMARY KEY,
    processed_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
