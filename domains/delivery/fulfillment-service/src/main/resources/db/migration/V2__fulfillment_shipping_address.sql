-- =============================================================================
-- fulfillment_db V2: keep the shipping address of the order being fulfilled.
-- It arrives in ORDER_CONFIRMED and must be passed on in FULFILLMENT_PACKED
-- (shipping-service creates the label from that event and never calls back).
-- Reflected in data-model/sql/04-fulfillment_db.sql, 04-fulfillment_shipping_db.md
-- and the diagram.
-- =============================================================================

ALTER TABLE fulfillments ADD COLUMN shipping_address JSONB NOT NULL;   -- snapshot copied from ORDER_CONFIRMED
