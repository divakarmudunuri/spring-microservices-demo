-- =============================================================================
-- 02-user_db.sql  (user-service)  →  Flyway: user-service V1__init.sql
-- Users come from three places:
--   GOOGLE : customers, created on first Google login (just-in-time registration)
--   OKTA   : admins, created on first Okta login (must be in the Okta admin group)
--   LOCAL  : the two seeded sample users. They sign in through the dev identity
--            provider (dev-idp/, a mock OIDC server), `local` profile only.
--            external_subject = the dev-idp username (sample-customer / sample-admin).
-- Nobody has a password in this database: every sign-in goes through an OIDC
-- provider (Google, Okta, or the dev-idp).
-- =============================================================================

CREATE OR REPLACE FUNCTION set_updated_at() RETURNS trigger AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TABLE users (
    id                UUID          PRIMARY KEY,                -- internal id: the `sub` of internal JWTs
    email             VARCHAR(320)  NOT NULL,                   -- stored lower-case
    full_name         VARCHAR(200)  NOT NULL,
    role              VARCHAR(10)   NOT NULL,
    status            VARCHAR(10)   NOT NULL DEFAULT 'ACTIVE',
    auth_provider     VARCHAR(10)   NOT NULL,
    external_subject  VARCHAR(255)  NOT NULL,                   -- `sub` from Google / Okta / dev-idp
    last_login_at     TIMESTAMPTZ,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT ck_users_role CHECK (role IN ('ADMIN','CUSTOMER')),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE','SUSPENDED')),
    CONSTRAINT ck_users_provider CHECK (auth_provider IN ('GOOGLE','OKTA','LOCAL')),
    CONSTRAINT ck_users_email_lower CHECK (email = lower(email)),
    -- Google logins are always customers, Okta logins are always admins
    CONSTRAINT ck_users_provider_role CHECK (
        auth_provider = 'LOCAL' OR
        (auth_provider = 'GOOGLE' AND role = 'CUSTOMER') OR
        (auth_provider = 'OKTA'   AND role = 'ADMIN')),
    -- the identity key: who the identity provider says this is
    CONSTRAINT uq_users_provider_subject UNIQUE (auth_provider, external_subject),
    -- the same email may exist once per provider (e.g. a LOCAL sample user and a real Google user)
    CONSTRAINT uq_users_provider_email UNIQUE (auth_provider, email)
);
CREATE INDEX ix_users_role_status ON users (role, status);
CREATE TRIGGER trg_users_updated_at BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE addresses (
    id           UUID          PRIMARY KEY,
    user_id      UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    full_name    VARCHAR(200)  NOT NULL,                         -- recipient
    line1        VARCHAR(200)  NOT NULL,
    line2        VARCHAR(200),
    city         VARCHAR(100)  NOT NULL,
    state        VARCHAR(100)  NOT NULL,
    postal_code  VARCHAR(20)   NOT NULL,
    country      CHAR(2)       NOT NULL,                         -- ISO 3166-1 alpha-2
    phone        VARCHAR(30),
    is_default   BOOLEAN       NOT NULL DEFAULT false,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT ck_addresses_country CHECK (country ~ '^[A-Z]{2}$')
);
CREATE INDEX ix_addresses_user ON addresses (user_id);
-- at most one default address per user
CREATE UNIQUE INDEX uq_addresses_one_default ON addresses (user_id) WHERE is_default;
CREATE TRIGGER trg_addresses_updated_at BEFORE UPDATE ON addresses
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
