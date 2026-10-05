-- =============================================================================
-- 05-shipping_db.sql  (shipping-service)  →  Flyway: shipping-service V1__init.sql
-- =============================================================================

CREATE OR REPLACE FUNCTION set_updated_at() RETURNS trigger AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TABLE shipments (
    id                  UUID          PRIMARY KEY,
    order_id            UUID          NOT NULL,
    user_id             UUID          NOT NULL,                 -- from the event; used for ownership checks
    tracking_number     VARCHAR(30)   NOT NULL,
    carrier             VARCHAR(30)   NOT NULL,
    status              VARCHAR(20)   NOT NULL,
    shipping_address    JSONB         NOT NULL,                 -- copied from the event
    estimated_delivery  DATE          NOT NULL,
    delivered_at        TIMESTAMPTZ,
    next_step_at        TIMESTAMPTZ,                            -- when the simulator should advance it
    version             BIGINT        NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT uq_shipments_order UNIQUE (order_id),            -- one shipment per order (idempotency)
    CONSTRAINT uq_shipments_tracking_number UNIQUE (tracking_number),
    CONSTRAINT ck_shipments_status CHECK (status IN (
        'LABEL_CREATED','PICKED_UP','IN_TRANSIT','OUT_FOR_DELIVERY','DELIVERED')),
    CONSTRAINT ck_shipments_delivered CHECK ((status = 'DELIVERED') = (delivered_at IS NOT NULL))
);
CREATE INDEX ix_shipments_user_created ON shipments (user_id, created_at DESC);
CREATE INDEX ix_shipments_status_created ON shipments (status, created_at DESC);  -- admin listing
CREATE INDEX ix_shipments_due ON shipments (next_step_at) WHERE status <> 'DELIVERED';
CREATE TRIGGER trg_shipments_updated_at BEFORE UPDATE ON shipments
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE outbox_event (
    id            UUID          PRIMARY KEY,
    topic         VARCHAR(100)  NOT NULL,                       -- shipping-events
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
