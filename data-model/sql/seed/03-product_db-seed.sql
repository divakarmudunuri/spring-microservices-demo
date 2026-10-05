-- =============================================================================
-- product_db seed (local profile only)  →  Flyway: product-service db/seed/V2__seed.sql
-- Fixed UUIDs: product ids match order_db.inventory.product_id.
-- Images are placeholder SVGs served by the frontend from /products/<slug>.svg
-- Availability levels use the default low-stock threshold of 5.
-- =============================================================================

INSERT INTO categories (id, slug, name, sort_order) VALUES
    ('10000000-0000-4000-8000-000000000001', 'electronics', 'Electronics', 1),
    ('10000000-0000-4000-8000-000000000002', 'home-kitchen', 'Home & Kitchen', 2),
    ('10000000-0000-4000-8000-000000000003', 'outdoor', 'Outdoor', 3);

-- created_at is staggered so "new arrivals" (newest first) has a stable order
INSERT INTO products (id, sku, slug, name, description, category_id, image_url, price, currency, active, featured, created_at) VALUES
    ('20000000-0000-4000-8000-000000000001', 'EL-EARBUD-01', 'wireless-earbuds', 'Wireless Earbuds',
     'Compact Bluetooth earbuds with a charging case and 24-hour total battery life.',
     '10000000-0000-4000-8000-000000000001', '/products/wireless-earbuds.svg', 79.99, 'USD', true, true, now() - interval '30 days'),
    ('20000000-0000-4000-8000-000000000002', 'EL-CHRG-65W', 'usb-c-charger-65w', 'USB-C Charger 65W',
     'Two-port fast charger for laptops, tablets and phones.',
     '10000000-0000-4000-8000-000000000001', '/products/usb-c-charger-65w.svg', 39.99, 'USD', true, false, now() - interval '28 days'),
    ('20000000-0000-4000-8000-000000000003', 'EL-KEYB-MEC', 'mechanical-keyboard', 'Mechanical Keyboard',
     'Tenkeyless keyboard with hot-swappable switches and a detachable cable.',
     '10000000-0000-4000-8000-000000000001', '/products/mechanical-keyboard.svg', 129.00, 'USD', true, true, now() - interval '20 days'),
    ('20000000-0000-4000-8000-000000000004', 'EL-MON-27-4K', 'monitor-27-4k', '27" 4K Monitor',
     '27-inch IPS display, 3840x2160, USB-C with 90 W power delivery.',
     '10000000-0000-4000-8000-000000000001', '/products/monitor-27-4k.svg', 349.00, 'USD', true, false, now() - interval '15 days'),
    ('20000000-0000-4000-8000-000000000005', 'HK-POUR-SET', 'pour-over-coffee-set', 'Pour-Over Coffee Set',
     'Glass carafe, stainless filter and a gooseneck kettle.',
     '10000000-0000-4000-8000-000000000002', '/products/pour-over-coffee-set.svg', 54.50, 'USD', true, true, now() - interval '25 days'),
    ('20000000-0000-4000-8000-000000000006', 'HK-SKIL-10', 'cast-iron-skillet-10', 'Cast Iron Skillet 10"',
     'Pre-seasoned 10-inch skillet for stovetop, oven and campfire.',
     '10000000-0000-4000-8000-000000000002', '/products/cast-iron-skillet-10.svg', 34.95, 'USD', true, false, now() - interval '22 days'),
    ('20000000-0000-4000-8000-000000000007', 'HK-MUG-SET4', 'ceramic-mug-set-4', 'Ceramic Mug Set (4)',
     'Four 350 ml stoneware mugs, dishwasher and microwave safe.',
     '10000000-0000-4000-8000-000000000002', '/products/ceramic-mug-set-4.svg', 29.00, 'USD', true, false, now() - interval '10 days'),
    ('20000000-0000-4000-8000-000000000008', 'HK-BOARD-BAM', 'bamboo-cutting-board', 'Bamboo Cutting Board',
     'Large reversible board with a juice groove.',
     '10000000-0000-4000-8000-000000000002', '/products/bamboo-cutting-board.svg', 24.99, 'USD', true, false, now() - interval '5 days'),
    ('20000000-0000-4000-8000-000000000009', 'OD-BOTTLE-1L', 'insulated-water-bottle', 'Insulated Water Bottle 1L',
     'Double-wall stainless bottle that keeps drinks cold for 24 hours.',
     '10000000-0000-4000-8000-000000000003', '/products/insulated-water-bottle.svg', 27.50, 'USD', true, true, now() - interval '12 days'),
    ('20000000-0000-4000-8000-000000000010', 'OD-PACK-25L', 'daypack-25l', 'Daypack 25L',
     'Lightweight hiking pack with a hydration sleeve and rain cover.',
     '10000000-0000-4000-8000-000000000003', '/products/daypack-25l.svg', 64.00, 'USD', true, false, now() - interval '8 days'),
    ('20000000-0000-4000-8000-000000000011', 'OD-LAMP-LED', 'led-headlamp', 'LED Headlamp',
     'Rechargeable 400-lumen headlamp with red night mode.',
     '10000000-0000-4000-8000-000000000003', '/products/led-headlamp.svg', 22.00, 'USD', true, false, now() - interval '3 days'),
    ('20000000-0000-4000-8000-000000000012', 'OD-HAMMOCK', 'camping-hammock', 'Camping Hammock',
     'Ripstop nylon hammock with tree straps; packs to the size of a grapefruit.',
     '10000000-0000-4000-8000-000000000003', '/products/camping-hammock.svg', 45.00, 'USD', true, false, now() - interval '1 days');

INSERT INTO product_availability (product_id, level, last_event_at) VALUES
    ('20000000-0000-4000-8000-000000000001', 'IN_STOCK', now()),
    ('20000000-0000-4000-8000-000000000002', 'IN_STOCK', now()),
    ('20000000-0000-4000-8000-000000000003', 'LOW_STOCK', now()),
    ('20000000-0000-4000-8000-000000000004', 'OUT_OF_STOCK', now()),
    ('20000000-0000-4000-8000-000000000005', 'IN_STOCK', now()),
    ('20000000-0000-4000-8000-000000000006', 'IN_STOCK', now()),
    ('20000000-0000-4000-8000-000000000007', 'LOW_STOCK', now()),
    ('20000000-0000-4000-8000-000000000008', 'IN_STOCK', now()),
    ('20000000-0000-4000-8000-000000000009', 'IN_STOCK', now()),
    ('20000000-0000-4000-8000-000000000010', 'IN_STOCK', now()),
    ('20000000-0000-4000-8000-000000000011', 'IN_STOCK', now()),
    ('20000000-0000-4000-8000-000000000012', 'IN_STOCK', now());
