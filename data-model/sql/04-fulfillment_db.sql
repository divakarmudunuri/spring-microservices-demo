-- =============================================================================
-- 04-fulfillment_db.sql  (fulfillment-service)  →  Flyway: fulfillment-service V1__init.sql
-- =============================================================================

CREATE OR REPLACE FUNCTION set_updated_at() RETURNS trigger AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TABLE fulfillments (
    id              UUID          PRIMARY KEY,
    order_id        UUID          NOT NULL,
    user_id         UUID          NOT NULL,                     -- from the event envelope
    status          VARCHAR(10)   NOT NULL,
    warehouse_code  VARCHAR(20)   NOT NULL,
    failure_reason  VARCHAR(200),
    next_step_at    TIMESTAMPTZ,                                -- when the simulator should advance it
    version         BIGINT        NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT uq_fulfillments_order UNIQUE (order_id),        -- one fulfillment per order (idempotency)
    CONSTRAINT ck_fulfillments_status CHECK (status IN ('RECEIVED','PICKING','PACKED','FAILED')),
    CONSTRAINT ck_fulfillments_failure CHECK ((status = 'FAILED') = (failure_reason IS NOT NULL))
);
-- the simulator polls for work that is due
CREATE INDEX ix_fulfillments_due ON fulfillments (next_step_at) WHERE status IN ('RECEIVED','PICKING');
CREATE TRIGGER trg_fulfillments_updated_at BEFORE UPDATE ON fulfillments
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE fulfillment_items (
    id              BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    fulfillment_id  UUID         NOT NULL REFERENCES fulfillments (id) ON DELETE CASCADE,
    product_id      UUID         NOT NULL,
    quantity        INT          NOT NULL,

    CONSTRAINT uq_fulfillment_items UNIQUE (fulfillment_id, product_id),
    CONSTRAINT ck_fulfillment_items_quantity CHECK (quantity > 0)
);

CREATE TABLE outbox_event (
    id            UUID          PRIMARY KEY,
    topic         VARCHAR(100)  NOT NULL,                       -- fulfillment-events
    aggregate_id  UUID          NOT NULL,                       -- orderId (Kafka key)
    event_type    VARCHAR(50)   NOT NULL,
    payload       JSONB         NOT NULL,
    trace_parent  VARCHAR(100),
    attempts      INT           NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ
);
CREATE INDEX ix_outbox_unpublished ON outbox_event (created_at) WHERE published_at IS NULL;

CREATE TABLE processed_event (
    event_id      UUID         PRIMARY KEY,
    processed_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
