-- =============================================================================
-- user_db seed (local profile only)  →  Flyway: user-service db/seed/V2__seed.sql
-- The two SAMPLE users (auth_provider = LOCAL). They sign in through the dev
-- identity provider (dev-idp/, a mock OIDC server) by typing their username on
-- its login page, or with dev-idp/dev-token.sh. No passwords anywhere.
--   sample-customer  → acts like a Google customer   (dev-idp issuer dev-customer)
--   sample-admin     → acts like an Okta admin       (dev-idp issuer dev-admin)
-- Real users come from Google (customers) and Okta (admins) and are created
-- automatically on their first sign-in.
-- =============================================================================

INSERT INTO users (id, email, full_name, role, status, auth_provider, external_subject) VALUES
    ('00000000-0000-4000-8000-0000000000a1', 'admin@demo.local',    'Demo Admin',    'ADMIN',    'ACTIVE', 'LOCAL', 'sample-admin'),
    ('00000000-0000-4000-8000-0000000000c1', 'customer@demo.local', 'Demo Customer', 'CUSTOMER', 'ACTIVE', 'LOCAL', 'sample-customer');

INSERT INTO addresses (id, user_id, full_name, line1, line2, city, state, postal_code, country, phone, is_default) VALUES
    ('30000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-0000000000c1', 'Demo Customer',
     '100 Example Street', 'Apt 4B', 'Detroit', 'MI', '48226', 'US', '+1-555-0100', true);
