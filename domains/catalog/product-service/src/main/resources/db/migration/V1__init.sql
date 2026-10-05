-- =============================================================================
-- 03-product_db.sql  (product-service)  →  Flyway: product-service V1__init.sql
-- Public catalog. No exact stock here: product_availability is a read model
-- built from `inventory-events` and exposes only a level.
-- =============================================================================

CREATE OR REPLACE FUNCTION set_updated_at() RETURNS trigger AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TABLE categories (
    id          UUID          PRIMARY KEY,
    slug        VARCHAR(80)   NOT NULL,
    name        VARCHAR(120)  NOT NULL,
    sort_order  INT           NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT uq_categories_slug UNIQUE (slug),
    CONSTRAINT ck_categories_slug CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$')
);
CREATE TRIGGER trg_categories_updated_at BEFORE UPDATE ON categories
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE products (
    id           UUID          PRIMARY KEY,                     -- = order_db.inventory.product_id
    sku          VARCHAR(40)   NOT NULL,
    slug         VARCHAR(120)  NOT NULL,
    name         VARCHAR(200)  NOT NULL,
    description  TEXT          NOT NULL,
    category_id  UUID          NOT NULL REFERENCES categories (id),
    image_url    VARCHAR(300)  NOT NULL,                        -- relative path served by the frontend
    price        NUMERIC(12,2) NOT NULL,
    currency     CHAR(3)       NOT NULL DEFAULT 'USD',
    active       BOOLEAN       NOT NULL DEFAULT true,
    featured     BOOLEAN       NOT NULL DEFAULT false,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT uq_products_sku UNIQUE (sku),
    CONSTRAINT uq_products_slug UNIQUE (slug),
    CONSTRAINT ck_products_slug CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT ck_products_price CHECK (price > 0)
);
CREATE INDEX ix_products_category_active ON products (category_id, active);
CREATE INDEX ix_products_featured ON products (featured) WHERE featured AND active;
CREATE INDEX ix_products_created ON products (created_at DESC);                    -- "new arrivals"
-- simple case-insensitive search (`q=`); replace with full-text search later
CREATE INDEX ix_products_name_lower ON products (lower(name) text_pattern_ops);
CREATE TRIGGER trg_products_updated_at BEFORE UPDATE ON products
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE product_availability (
    product_id     UUID         PRIMARY KEY REFERENCES products (id) ON DELETE CASCADE,
    level          VARCHAR(15)  NOT NULL,
    last_event_at  TIMESTAMPTZ  NOT NULL,                       -- older events are ignored
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ck_availability_level CHECK (level IN ('IN_STOCK','LOW_STOCK','OUT_OF_STOCK'))
);
CREATE INDEX ix_availability_level ON product_availability (level);
CREATE TRIGGER trg_product_availability_updated_at BEFORE UPDATE ON product_availability
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE processed_event (
    event_id      UUID         PRIMARY KEY,
    processed_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
