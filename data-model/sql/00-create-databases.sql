-- =============================================================================
-- 00-create-databases.sql
-- Creates one database + one owner role per service (database-per-service).
-- Run once as the Postgres superuser (the postgres container runs this from
-- /docker-entrypoint-initdb.d on first start).
--
-- LOCAL DEVELOPMENT ONLY: the passwords below are throwaway defaults so the
-- stack starts with zero setup. Real environments must supply their own
-- credentials via environment variables / a secrets manager.
--
-- order-tracking-service and cart-service use DynamoDB (see ../dynamodb/).
-- =============================================================================

CREATE ROLE order_svc       LOGIN PASSWORD 'order_local_pw';
CREATE ROLE user_svc        LOGIN PASSWORD 'user_local_pw';
CREATE ROLE product_svc     LOGIN PASSWORD 'product_local_pw';
CREATE ROLE fulfillment_svc LOGIN PASSWORD 'fulfillment_local_pw';
CREATE ROLE shipping_svc    LOGIN PASSWORD 'shipping_local_pw';

CREATE DATABASE order_db       OWNER order_svc;
CREATE DATABASE user_db        OWNER user_svc;
CREATE DATABASE product_db     OWNER product_svc;
CREATE DATABASE fulfillment_db OWNER fulfillment_svc;
CREATE DATABASE shipping_db    OWNER shipping_svc;

-- No service may connect to another service's database.
REVOKE CONNECT ON DATABASE order_db       FROM PUBLIC;
REVOKE CONNECT ON DATABASE user_db        FROM PUBLIC;
REVOKE CONNECT ON DATABASE product_db     FROM PUBLIC;
REVOKE CONNECT ON DATABASE fulfillment_db FROM PUBLIC;
REVOKE CONNECT ON DATABASE shipping_db    FROM PUBLIC;
