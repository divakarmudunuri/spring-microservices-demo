-- =============================================================================
-- user_db-tests.sql — run AFTER 02-user_db.sql + seed. Rolled back at the end.
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

-- a Google customer created on first login (just-in-time registration)
INSERT INTO users (id, email, full_name, role, auth_provider, external_subject)
VALUES ('00000000-0000-4000-8000-0000000000c2', 'jane@gmail.com', 'Jane Doe', 'CUSTOMER', 'GOOGLE', '108111222333444555666');
DO $$ BEGIN RAISE NOTICE 'PASS  Google customer can be provisioned'; END $$;

-- an Okta admin created on first login
INSERT INTO users (id, email, full_name, role, auth_provider, external_subject)
VALUES ('00000000-0000-4000-8000-0000000000a2', 'ops@example.com', 'Ops Admin', 'ADMIN', 'OKTA', '00u1abcdEFGHijkl5d7');
DO $$ BEGIN RAISE NOTICE 'PASS  Okta admin can be provisioned'; END $$;

-- same email as the LOCAL sample customer, but via Google: allowed (different provider)
INSERT INTO users (id, email, full_name, role, auth_provider, external_subject)
VALUES (gen_random_uuid(), 'customer@demo.local', 'Same Email', 'CUSTOMER', 'GOOGLE', 'g-sub-2');
DO $$ BEGIN RAISE NOTICE 'PASS  same email allowed once per provider'; END $$;

SELECT pg_temp.expect_fail('same Google subject cannot register twice',
  $q$INSERT INTO users (id, email, full_name, role, auth_provider, external_subject) VALUES (gen_random_uuid(), 'other@gmail.com', 'X', 'CUSTOMER', 'GOOGLE', '108111222333444555666')$q$);
SELECT pg_temp.expect_fail('a Google login can never be an ADMIN',
  $q$INSERT INTO users (id, email, full_name, role, auth_provider, external_subject) VALUES (gen_random_uuid(), 'evil@gmail.com', 'X', 'ADMIN', 'GOOGLE', 'g-sub-3')$q$);
SELECT pg_temp.expect_fail('an Okta login can never be a CUSTOMER',
  $q$INSERT INTO users (id, email, full_name, role, auth_provider, external_subject) VALUES (gen_random_uuid(), 'c@example.com', 'X', 'CUSTOMER', 'OKTA', 'okta-sub-3')$q$);
SELECT pg_temp.expect_fail('every user needs an external subject (even LOCAL)',
  $q$INSERT INTO users (id, email, full_name, role, auth_provider) VALUES (gen_random_uuid(), 'l@demo.local', 'X', 'CUSTOMER', 'LOCAL')$q$);
SELECT pg_temp.expect_fail('dev-idp username maps to one sample user only',
  $q$INSERT INTO users (id, email, full_name, role, auth_provider, external_subject) VALUES (gen_random_uuid(), 'dup@demo.local', 'X', 'CUSTOMER', 'LOCAL', 'sample-customer')$q$);

-- the sample users are found by their dev-idp username
DO $$
BEGIN
    IF (SELECT id FROM users WHERE auth_provider = 'LOCAL' AND external_subject = 'sample-customer') <> '00000000-0000-4000-8000-0000000000c1'
    OR (SELECT id FROM users WHERE auth_provider = 'LOCAL' AND external_subject = 'sample-admin')    <> '00000000-0000-4000-8000-0000000000a1' THEN
        RAISE EXCEPTION 'FAIL  sample users not found by external_subject';
    END IF;
    RAISE NOTICE 'PASS  sample users are found by their dev-idp username';
END $$;
SELECT pg_temp.expect_fail('emails are stored lower-case',
  $q$INSERT INTO users (id, email, full_name, role, auth_provider, external_subject) VALUES (gen_random_uuid(), 'Mixed@Gmail.com', 'X', 'CUSTOMER', 'GOOGLE', 'g-sub-5')$q$);
SELECT pg_temp.expect_fail('only one default address per user',
  $q$INSERT INTO addresses (id, user_id, full_name, line1, city, state, postal_code, country, is_default) VALUES (gen_random_uuid(), '00000000-0000-4000-8000-0000000000c1', 'X', '1 Road', 'Detroit', 'MI', '48226', 'US', true)$q$);

ROLLBACK;
