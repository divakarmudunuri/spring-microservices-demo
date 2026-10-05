# CLAUDE.md — spring-microservices-demo

Instructions for Claude when working in this repository. Read this whole file before making changes.

## 1. What this project is

An e-commerce order flow split into microservices, built to show these patterns in working code:

1. **Local ACID transaction (`@Transactional`)** — checking stock, decrementing stock, and taking payment happen in **one database transaction**: all succeed or all roll back.
2. **API composition / aggregator** — independent downstream calls are fanned out **in parallel with `CompletableFuture`** and combined.
3. **Declarative HTTP clients (OpenFeign)** — every synchronous service-to-service call is a Feign client.
4. **Event-driven workflow with Apache Kafka** — after checkout, fulfillment → shipping → delivery runs asynchronously over Kafka, using the **transactional outbox** pattern.
5. **Order tracking (CQRS read model on DynamoDB)** — a dedicated service records every status change of every order, from the moment it is initiated, in **Amazon DynamoDB** (polyglot persistence: Postgres where ACID matters, DynamoDB for an append-heavy, key-based read model).
6. **Saga (scaled down)** — one compensation path only: if fulfillment fails after payment, refund and restock.
7. **Discovery and routing** — Eureka + client-side load balancing.
8. **API gateway** — one entry point that routes, filters, and protects external traffic.
9. **Resilience** — timeouts, retries, circuit breakers, bulkheads, and fallbacks on every outbound call.
10. **Observability** — distributed tracing (across HTTP *and* Kafka), metrics, and correlated logs.
11. **Security with federated login and roles** — customers sign in (and register) with **Google**, admins sign in with **Okta**; both flows run at the **nginx** edge via oauth2-proxy. The gateway validates those tokens and exchanges them for **internal JWTs**; services check roles and ownership. Two roles: **ADMIN** and **CUSTOMER**; anonymous visitors can browse.
12. **Online storefront** — anonymous product browsing (cached, with stock *levels* fed by Kafka), a guest cart that merges into the user's cart on login, checkout from the cart, a home-page **BFF**, and an Angular frontend in `frontend/`.
13. **Edge proxy (nginx)** — the single front door for the UI *and* the API (`http://localhost`), with public browsing, login flows, CSRF guard, rate limits and security headers. Already written and tested in `nginx-proxy/`.

It is a learning and interview-portfolio project. Favor clarity and well-named code over cleverness. Every pattern should be easy to find and explain from the README.

## 2. Tech stack (fixed — do not change without asking)

| Item | Version / choice |
|---|---|
| Build | **Gradle 8.14** via the Gradle Wrapper (**Groovy DSL**: `*.gradle` — do not use Kotlin DSL / `*.gradle.kts`) |
| JDK | **Azul Zulu 17** via Gradle toolchains (`vendor = JvmVendorSpec.AZUL`, `languageVersion = 17`) |
| Framework | Spring Boot **3.5.x** (latest patch) + Spring Cloud **2025.0.x** (the release train compatible with Boot 3.5). Check the Spring Cloud compatibility table before pinning. |
| Discovery | Spring Cloud Netflix Eureka (server + clients) |
| Gateway | Spring Cloud Gateway (reactive / WebFlux flavor) |
| Load balancing | Spring Cloud LoadBalancer |
| HTTP clients | **Spring Cloud OpenFeign** with the Apache HttpClient 5 transport (`feign-hc5`) and `feign-micrometer` |
| Messaging | **Apache Kafka** (KRaft mode, single broker locally) with **Spring for Apache Kafka**, JSON payloads |
| Resilience | Resilience4j (Spring Boot 3 starter, annotation style) |
| Database | PostgreSQL 16, **one database per service**, migrations with Flyway — **except `order-tracking-service` and `cart-service`, which use Amazon DynamoDB**. Reviewed schemas live in `data-model/` (section 4). |
| DynamoDB access | **AWS SDK for Java v2** — `DynamoDbEnhancedClient` (`dynamodb-enhanced`) with `@DynamoDbBean` items; versions via the AWS SDK BOM in the catalog. No Spring Data DynamoDB / Spring Cloud AWS. **DynamoDB Local** (`amazon/dynamodb-local`) for local runs and tests. |
| Persistence | Spring Data JPA |
| Observability | Spring Boot Actuator, Micrometer Tracing with the OpenTelemetry bridge, OTLP export to Jaeger, Micrometer Prometheus registry, Prometheus + Grafana |
| Security | Spring Security 6 (from Boot 3.5). **Gateway:** reactive multi-issuer OAuth2 **resource server** (Google, Okta, and in `local` the dev identity provider) + token exchange. **user-service:** just-in-time user registration, issues **internal** RS256 JWTs (`spring-security-oauth2-jose`, `NimbusJwtEncoder`), publishes a JWKS. No passwords anywhere: in `local`, the sample users sign in through a mock OIDC server (`dev-idp/`). **Other services:** servlet resource servers trusting only the internal issuer. See 6.11. |
| Identity providers | **Google** (OIDC) for customers; **Okta** Integrator Free Plan (OIDC, custom authorization server `default`, group `smd-admins`) for admins. Setup guides: `nginx-proxy/README.md`, `okta-login-setup/README.md`. |
| Dev identity provider | **mock-oauth2-server 3.0.3** (`ghcr.io/navikt/mock-oauth2-server`; there is no 3.1.x) in `dev-idp/`: stands in for Google and Okta in `local`, so the sample users go through the real sign-in path. |
| Edge proxy | **nginx 1.28** (official image) + **oauth2-proxy v7.15** (two instances: Google, Okta) + Redis session store, all in `nginx-proxy/docker-compose.yml`. |
| Testing | JUnit 5, AssertJ, Testcontainers (PostgreSQL, Kafka, DynamoDB Local via `GenericContainer`), WireMock for Feign client tests, Awaitility for async assertions |
| Caching | Caffeine (Spring Cache) in product-service, plus HTTP `Cache-Control` / `ETag` on public catalog responses |
| Frontend | **Angular** (current stable major, standalone components, TypeScript strict mode) in `frontend/`, built with npm, **not** part of the Gradle build. Confirm the Angular version with the user before scaffolding. |
| Local runtime | Docker Compose |

**Java 17 note:** virtual threads are *not* available (they need Java 21+). Do not enable `spring.threads.virtual.enabled`. Parallelism comes from `CompletableFuture` on a **bounded, named, context-propagating executor** (see 6.2). Leave a short comment where the executor is defined explaining this, and that Java 21 virtual threads would be the alternative.

**Feign is blocking.** That is fine and intended here: blocking Feign calls + `CompletableFuture` fan-out is the design. Do not use `WebClient` anywhere in the services (the gateway is the only reactive component, and it uses its own routing, not a client).

Do not add Lombok, MapStruct, or other code generators unless asked. Use Java records for DTOs and event payloads.

## 3. Services and repository layout

Services are grouped by **business domain** (`domains/`), with infrastructure (`platform/`) and UI-shaping (`experience/`) services kept apart:

| Group | Module | Folder | Port | Role | Database |
|---|---|---|---|---|---|
| edge | `nginx-proxy` | `nginx-proxy/` | 80 | Serves the Angular app, runs Google/Okta login via oauth2-proxy, forwards `/api/**` to the gateway (6.11) | Redis (sessions) |
| platform | `discovery-server` | `platform/discovery-server/` | 8761 | Eureka server | — |
| platform | `api-gateway` | `platform/api-gateway/` | 8080 | Behind nginx. Validates Google/Okta tokens, **exchanges them for internal JWTs**, role-based route rules, routing, resilience | — |
| experience | `storefront-bff` | `experience/storefront-bff/` | 8088 | Backend-for-frontend: composes home-page data in parallel; no data of its own | — |
| identity | `user-service` | `domains/identity/user-service/` | 8082 | Users (Google customers, Okta admins, 2 local sample users), addresses; **just-in-time registration**, **token exchange → internal JWT**, JWKS; maps dev-idp sign-ins to the sample users (`local` only) | `user_db` |
| catalog | `product-service` | `domains/catalog/product-service/` | 8083 | Product catalog: categories, products, prices, stock levels | `product_db` |
| sales | `cart-service` | `domains/sales/cart-service/` | 8087 | Guest and customer carts, merge on login, cleared after checkout | DynamoDB table `carts` |
| sales | `order-service` | `domains/sales/order-service/` | 8081 | Checkout: orders, **inventory**, **payments**/wallets (one transaction), delivery acknowledgement, admin views of orders/payments/inventory, aggregator endpoint, outbox publisher, compensation | `order_db` |
| delivery | `fulfillment-service` | `domains/delivery/fulfillment-service/` | 8084 | Picks and packs confirmed orders (Kafka-driven) | `fulfillment_db` |
| delivery | `shipping-service` | `domains/delivery/shipping-service/` | 8085 | Creates shipments and simulates delivery (Kafka-driven) | `shipping_db` |
| delivery | `order-tracking-service` | `domains/delivery/order-tracking-service/` | 8086 | Consumes **all** order-related events and serves the timeline | DynamoDB table `order_tracking` |
| UI | `frontend` | `frontend/` | served by nginx on 80 | Angular storefront + admin screens (`/admin`) | — |

Why these groups:

- **`platform/`** is how the system runs (discovery, routing, security at the gateway); it has no business logic.
- **`experience/`** shapes data for one UI. It owns no data and holds no business rules, so it stays out of `domains/`.
- **`domains/`** follows ownership and events:
  - `sales` produces orders;
  - `delivery` reacts to them over Kafka;
  - `catalog` and `identity` are read by everyone;
  - `order-tracking-service` is in `delivery` because customers use it as "where's my order?", even though it records events from every topic.
- Calls should mostly go *between* groups in that direction. A new service goes into the domain that owns its data. Ask before creating a new group.

**Why inventory and payment live inside `order-service`:** `@Transactional` only covers one database. To make "enough stock → decrement stock → take payment → confirm order" truly atomic, those tables must share a database, so they form one bounded context ("checkout") in `order-service`. Payment is simulated with an internal **customer wallet** (a balance that is debited), which can take part in the transaction. The README must explain this trade-off, and that a real card processor is an external system that cannot join a DB transaction (it would need authorize → capture with a compensating refund). There is no separate payment or inventory service.

```
spring-microservices-demo/
├── CLAUDE.md
├── README.md
├── settings.gradle               # includes all modules below
├── build.gradle                  # root: no code, only shared config if needed
├── gradle.properties
├── gradle/
│   ├── libs.versions.toml        # version catalog: ALL versions live here
│   └── wrapper/                  # Gradle 8.14 wrapper
├── gradlew / gradlew.bat
├── build-logic/                  # included build with convention plugins
│   ├── settings.gradle
│   ├── build.gradle              # applies the `groovy-gradle-plugin` plugin
│   └── src/main/groovy/
│       ├── java-conventions.gradle             # toolchain (Azul 17), encoding, test setup
│       ├── spring-service-conventions.gradle   # Boot plugin, actuator, tracing, Eureka client, test deps
│       ├── feign-conventions.gradle            # OpenFeign, feign-hc5, feign-micrometer, Resilience4j
│       ├── kafka-conventions.gradle            # spring-kafka, Testcontainers Kafka
│       ├── dynamodb-conventions.gradle         # AWS SDK v2 BOM, dynamodb-enhanced, Testcontainers
│       └── security-conventions.gradle         # oauth2-resource-server, spring-security-test
├── docs/
│   ├── events.md                 # Kafka topics and event contracts (source of truth)
│   └── security.md               # roles, endpoint permission matrix, token format
├── scripts/
│   └── generate-dev-keys.sh      # creates the local JWT RSA key pair in .local/keys/ (git-ignored)
├── data-model/                   # REVIEWED schemas, seed data, diagrams, SQL tests (section 4)
├── nginx-proxy/                  # nginx + oauth2-proxy (Google, Okta) + compose — written and tested (6.11)
├── okta-login-setup/             # guide: Okta account, admin group, app, securing /admin
├── dev-idp/                      # mock OIDC server standing in for Google/Okta so the sample users sign in via nginx (local only)
├── docker/
│   ├── docker-compose.yml        # backend infrastructure (Postgres, Kafka, DynamoDB Local, ...)
│   ├── postgres/init/            # mounts data-model/sql/00-create-databases.sql
│   ├── prometheus/prometheus.yml
│   └── grafana/provisioning/
├── platform/                     # every service folder already exists with a README.md (its spec summary);
│   ├── discovery-server/         # the code is added phase by phase (section 9)
│   └── api-gateway/
├── experience/
│   └── storefront-bff/
├── domains/
│   ├── identity/
│   │   └── user-service/
│   ├── catalog/
│   │   └── product-service/
│   ├── sales/
│   │   ├── cart-service/
│   │   └── order-service/
│   └── delivery/
│       ├── fulfillment-service/
│       ├── shipping-service/
│       └── order-tracking-service/
└── frontend/                     # Angular app (npm), see frontend/README.md
```

Rules:
- **Gradle project names stay flat** (`:order-service`, not `:domains:sales:order-service`), so commands stay short. Map each project to its folder in `settings.gradle`, and don't create Gradle projects for the group folders:
  ```groovy
  include 'order-service'
  project(':order-service').projectDir = file('domains/sales/order-service')
  ```
  Keep the mapping in one list in `settings.gradle`, in the same order as the table above.
- In this file, `<service>/` means the service's folder from the table (e.g. `domains/sales/order-service/`).
- Every version goes in `gradle/libs.versions.toml`. No version strings in module build files.
- Shared build config goes in `build-logic` convention plugins, not in `allprojects {}`/`subprojects {}` blocks.
- **Groovy DSL everywhere:** `settings.gradle`, `build.gradle`, and the convention plugins are all Groovy (`*.gradle`). Module build files are `<service folder>/build.gradle`. Use single-quoted strings unless interpolation is needed, and the `id 'x'` / `implementation libs.foo` style.
- Convention plugins are Groovy precompiled script plugins (`build-logic/build.gradle` applies `id 'groovy-gradle-plugin'`); modules apply them with `plugins { id 'spring-service-conventions' }`. The type-safe `libs` accessor is not available inside precompiled script plugins, so read the catalog there with `versionCatalogs.named('libs')` (e.g. `libs.findLibrary('...').get()`); module build files can use `libs.xyz` directly.
- Toolchain (in `java-conventions.gradle`): `java { toolchain { languageVersion = JavaLanguageVersion.of(17); vendor = JvmVendorSpec.AZUL } }`.
- Generate the wrapper with `gradle wrapper --gradle-version 8.14` (or write the wrapper files for 8.14) and always build with `./gradlew`.
- **No shared DTO/"common" library between services.** Each service owns its API models and event classes; consumers keep their own copy of the DTOs/events they need. `docs/events.md` is the contract. This is intentional (it keeps services decoupled) — do not "fix" it.

## 4. Data model and SQL scripts

**`data-model/` is the reviewed source of truth for every table, index, constraint, seed row and DynamoDB key.** Read `data-model/README.md` and the per-database docs before touching persistence. Do not redesign the schema while implementing; if something in it blocks you, stop and ask.

What's there (already written and verified against PostgreSQL 16: all schemas and seeds apply, and 24 constraint/rollback tests pass):

| `data-model/` path | Contents |
|---|---|
| `README.md`, `01-order_db.md` … `05-dynamodb.md` | design notes, Mermaid ER diagrams, status lifecycles, review questions |
| `sql/00-create-databases.sql` | the 5 Postgres databases + one owner role each (local-only passwords) |
| `sql/01-order_db.sql` … `sql/05-shipping_db.sql` | one schema per database |
| `sql/seed/` | 3 categories, 12 products (fixed UUIDs), inventory + availability, the 2 sample users, the sample customer's address and wallet |
| `sql/tests/` | constraint and checkout-rollback tests (run in a rolled-back transaction) |
| `dynamodb/` | `order_tracking` and `carts` table JSON, sample items, `create-tables.sh` |
| `diagrams/` | `.mmd` sources + rendered `.svg` |
| `scripts/verify-sql.sh` | applies everything to a throwaway Postgres container and runs the tests |

How it maps into the code:

- `docker/postgres/init/` mounts `data-model/sql/00-create-databases.sql`.
- `data-model/sql/0N-<db>.sql` is copied **verbatim** to `<service>/src/main/resources/db/migration/V1__init.sql`.
- `data-model/sql/seed/0N-<db>-seed.sql` → `<service>/src/main/resources/db/seed/V1000__seed.sql`, applied only in the `local` profile (`classpath:db/seed` added to `spring.flyway.locations` in `application-local.yml`). Version 1000 keeps the seed clear of real schema migrations (`V2`, `V3`, …); `application-local.yml` also sets `spring.flyway.out-of-order: true` so a new `V2` still applies to a local database that already has the seed.
- JPA maps to the schema, never the other way round: `spring.jpa.hibernate.ddl-auto=validate`.
- After a migration is committed, never edit it. Add `V<n>__*.sql` in the service **and** update `data-model/` (schema file, doc, diagram) in the same change.

Key points to respect (details in `data-model/`):

- `CHECK` constraints encode invariants. **`quantity_on_hand >= 0` and `balance >= 0` are the last line of defense against overselling and overdrawing**, so keep them even though the code checks first. Status/reason combinations are also enforced (e.g. a `REJECTED` order must have a `rejection_reason`).
- `orders.rejection_reason` values: `OUT_OF_STOCK`, `INSUFFICIENT_FUNDS`, `USER_INACTIVE`, `PRODUCT_NOT_FOUND`, `EMPTY_CART`, `NO_SHIPPING_ADDRESS`, `DEPENDENCY_UNAVAILABLE`.
- `user_db.users`: identity key is `(auth_provider, external_subject)`. `GOOGLE` users are always `CUSTOMER` and `OKTA` users always `ADMIN` (enforced by constraints). `external_subject` is required for everyone. **No passwords are stored anywhere.** `users.id` is the internal user id used everywhere else.
- **Sample users** (`auth_provider = LOCAL`; they sign in **only** through the dev identity provider in `dev-idp/`, `local` profile; real users come from Google/Okta):

| Role | Email | Sign in as (dev-idp) | Plays | Notes |
|---|---|---|---|---|
| `ADMIN` | `admin@demo.local` | `sample-admin` | an Okta admin in `smd-admins` | id `…0000000000a1`; no wallet, no address |
| `CUSTOMER` | `customer@demo.local` | `sample-customer` | a Google customer | id `…0000000000c1`; default address, wallet 500.00 USD |

  `external_subject` holds the dev-idp username. `dev-idp/dev-idp.json` also defines `sample-not-admin` (no admin group, no database row) to test refusals. Other test users (a second customer, a `SUSPENDED` one, a low-balance one) are created in tests, not seeded.
- **DynamoDB:** tables are created by a `DynamoDbTableInitializer` on startup in the `local`/`docker` profiles and in tests, using the same definitions as `data-model/dynamodb/*.table.json`. In real AWS they'd come from IaC (say so in the README). Cart writes are conditional on `version`: retry once on conflict, then `409`. Cart limits: 50 lines, 10 per product (`422` beyond).

## 5. The order flow end to end

```
Browser ──► nginx ──► api-gateway ──► order-service  POST /api/orders/checkout
                              │ 1. tx: order INITIATED (+ outbox ORDER_INITIATED)
                              │ 2. parallel Feign: user-service ║ product-service
                              │ 3. @Transactional checkout: stock check + decrement,
                              │    wallet debit, payment, order CONFIRMED (+ outbox)
                              │    └─ any failure → full rollback → tx: order REJECTED (+ outbox)
                              ▼
                 outbox relay ──► Kafka "order-events"
                                     │
            ┌────────────────────────┼─────────────────────────────┐
            ▼                        ▼                             ▼
   fulfillment-service      order-tracking-service          (order-service listens
   RECEIVED→PICKING→PACKED  records every event of           to fulfillment + shipping
   or FAILED                every topic as a timeline        events for its own status
            │ "fulfillment-events"                           and for compensation)
            ▼
   shipping-service
   LABEL_CREATED→PICKED_UP→IN_TRANSIT→OUT_FOR_DELIVERY→DELIVERED
            │ "shipping-events"
            ▼
   order-tracking-service, order-service
```

Checkout is **synchronous** (the caller gets `201 CONFIRMED` or an error with `REJECTED` immediately). Everything after checkout is **asynchronous** over Kafka.

## 6. Design patterns — required implementation

### 6.1 `@Transactional` checkout (order-service)

`POST /api/orders` (role `CUSTOMER`, requires an `Idempotency-Key` header) runs these steps, coordinated by `PlaceOrderUseCase`. **The user id always comes from the JWT (`sub`), never from the request body.** The body only holds items (`productId`, `quantity`). Placing the order *is* making the payment: the wallet is debited inside the checkout transaction.

1. **Idempotency:** if an order with that key exists, return it unchanged.
2. **Initiate** — `OrderInitiationService.initiate()` (`@Transactional`): insert the order as `INITIATED` with its items (no prices yet) and an `ORDER_INITIATED` outbox row. This commits on its own, so tracking sees the order even if checkout later fails.
3. **Pre-checkout lookups in parallel** (see 6.2): user-service (user must be `ACTIVE`; fetch the default address, and reject with `NO_SHIPPING_ADDRESS` if there is none, which is normal for a brand-new Google customer) and product-service (batch fetch prices; all products must exist and be active). **No remote calls happen inside the checkout transaction.**
4. **Checkout** — `CheckoutService.checkout(...)`, one `@Transactional` method (`isolation = READ_COMMITTED`, `rollbackFor = Exception.class`):
   1. For each item, **sorted by `product_id`** (consistent lock order prevents deadlocks), run an atomic conditional update:
      `UPDATE inventory SET quantity_on_hand = quantity_on_hand - :qty, version = version + 1 WHERE product_id = :id AND quantity_on_hand >= :qty`.
      If 0 rows are affected → throw `OutOfStockException`. Insert a `stock_movements` row for each decrement.
   2. Compute the total from the prices fetched in step 3; write `unit_price` on each item.
   3. Debit the wallet the same way: `UPDATE customer_wallets SET balance = balance - :amount WHERE user_id = :id AND balance >= :amount`. 0 rows → throw `InsufficientFundsException`. Insert a `wallet_transactions` row (`PAYMENT`).
   4. Insert a `payments` row (`CAPTURED`).
   5. Set the order to `CONFIRMED` with `total_amount` and `shipping_address`.
   6. Insert outbox rows `INVENTORY_RESERVED`, `PAYMENT_CAPTURED`, `ORDER_CONFIRMED` (the last one carries items, total, shipping address, and `cartId` when present, for downstream services). Also insert one `INVENTORY_CHANGED` outbox row per product whose stock changed (topic `inventory-events`, see 6.12).
   
   Any exception in any step rolls back **everything** in this method: no stock is decremented, no money taken, no events published.
5. **On a business failure**, `OrderRejectionService.reject(orderId, reason)` (`@Transactional`, separate bean, runs *after* the checkout transaction has rolled back): set `REJECTED` + `rejection_reason` and write an `ORDER_REJECTED` outbox row. On a technical failure (e.g. product-service down in step 3), mark `FAILED` with reason `DEPENDENCY_UNAVAILABLE`.

Responses: `201` with the confirmed order; `409` ProblemDetail for `OUT_OF_STOCK`; `402` for `INSUFFICIENT_FUNDS`; `422` for `USER_INACTIVE`/`PRODUCT_NOT_FOUND`/`NO_SHIPPING_ADDRESS`/`EMPTY_CART`; `503` for `DEPENDENCY_UNAVAILABLE`. Each body includes the order id so the client can still look it up in tracking.

Rules for transactions (put a short comment in `CheckoutService` explaining each):
- Transaction boundaries live in **separate Spring beans**. Calling a `@Transactional` method from another method in the same class bypasses the proxy, and the annotation silently does nothing.
- Business exceptions are **unchecked**. Also set `rollbackFor = Exception.class`, because by default Spring does not roll back on checked exceptions.
- Never call Feign or Kafka from inside a `@Transactional` method. Events go to the outbox table in the same transaction (6.4).
- Keep the transaction short; do all computation that doesn't need locks before it starts.
- `@Transactional(readOnly = true)` on query methods.

Required tests (Testcontainers PostgreSQL, real database — not mocks):
- Happy path: stock, wallet, payment, order status, and the three outbox rows are all correct.
- Second item out of stock → the **first item's stock is unchanged**, no payment, no wallet change, order `REJECTED`, outbox has only `ORDER_INITIATED` + `ORDER_REJECTED`.
- Insufficient funds → stock unchanged for every item.
- **Concurrency:** 10 threads order the last unit of the same product at once → exactly one `CONFIRMED`, nine `REJECTED`, stock ends at 0 (never negative).
- Same `Idempotency-Key` twice → one order, one payment.

### 6.2 API composition / aggregator with `CompletableFuture` (order-service)

- Define one executor bean `compositionExecutor`: a `ThreadPoolTaskExecutor` with a bounded pool and queue (e.g. core 16, max 32, queue 100), thread name prefix `compose-`, `CallerRunsPolicy` on rejection (comment why: back-pressure instead of dropped work), and **`setTaskDecorator(new ContextPropagatingTaskDecorator())`** so the trace context and MDC (traceId, correlationId) carry over to the worker threads. **Also propagate the Spring Security context** (wrap the executor in `DelegatingSecurityContextAsyncTaskExecutor`, or register a security-context `ThreadLocalAccessor`), because the Feign interceptor reads the caller's JWT from it on the worker thread (6.11). Test that a parallel call carries the `Authorization` header.
- **Never** call `supplyAsync` without an executor (the common ForkJoinPool is shared, unbounded in purpose, and loses context).
- Every future has an overall deadline (`orTimeout` / `completeOnTimeout`) in addition to the Feign timeouts.
- Wrap the fan-out in a small helper (`ParallelCalls` or similar) so the pattern is written once and is easy to show.

Use it in two places:
1. **Checkout pre-lookups** (6.1 step 3): user-service and product-service in parallel. No fallback here; if either fails, checkout fails (`422` or `503`).
2. **`GET /api/orders/{id}/details`** — the aggregator endpoint. Load the order locally, then fan out **in parallel** to:
   - user-service → customer name and email
   - product-service → product names and descriptions for the items (one batch call)
   - shipping-service → shipment, tracking number, ETA
   - order-tracking-service → latest status and timeline
   
   Combine them into `OrderDetailsResponse`. If a call fails or times out, return the rest with `"degraded": true` and `"unavailableSections": ["shipping", …]` — never a 500 for a partial failure.
- Add a debug log line with the total elapsed time vs. each call's time, and a README demo (using the chaos latency toggles) showing that parallel total ≈ slowest call, not the sum.

### 6.3 OpenFeign clients (all synchronous service-to-service calls)

- `@EnableFeignClients` in each calling service. Clients are `@FeignClient(name = "user-service", …)`, resolved through Eureka + Spring Cloud LoadBalancer. Never put a URL or port in a client.
- Layout per downstream service, inside the caller: `client/<service>/` with `XxxClient` (Feign interface), its DTO records, `XxxErrorDecoder`, and `XxxAdapter` (calls the client, applies resilience, maps DTOs → domain). The rest of the code uses only the adapter. Downstream DTOs never leave `client/`.
- **Resilience4j annotations go on the adapter methods** (`@CircuitBreaker`, `@Retry`, `@Bulkhead`), not on the Feign interface. Keep `spring.cloud.openfeign.circuitbreaker.enabled=false` so calls are not double-wrapped. Comment this choice.
- Timeouts per client in YAML: `spring.cloud.openfeign.client.config.<name>.connect-timeout` / `read-timeout`. No client uses the defaults.
- Leave Feign's own `Retryer` at `NEVER_RETRY`; Resilience4j owns retries.
- Use the `feign-hc5` transport with an explicitly sized connection pool.
- An `ErrorDecoder` per client maps `404` → not-found domain exception, `4xx` → non-retryable, `5xx`/IO errors → a retryable exception type that the Retry config targets.
- A `RequestInterceptor` forwards `X-Correlation-Id` (and `Idempotency-Key` where relevant), and **relays the caller's JWT** as `Authorization: Bearer …`, read from the `SecurityContext` (a `JwtAuthenticationToken`). If there is no authenticated caller, it sends no token. Never invent or forge tokens. Tracing headers are added automatically through `feign-micrometer`; verify this in Jaeger.
- Feign logger level `BASIC` in `local`, `NONE` elsewhere.
- Every client gets a WireMock test covering: success, 404, 500 with retry, slow response → timeout, circuit opening after repeated failures.

Who calls whom (Feign):
- order-service → user-service, product-service, shipping-service, order-tracking-service, **cart-service** (read the cart at checkout).
- cart-service → product-service (current prices, names, availability for cart display).
- storefront-bff → product-service (featured products, categories, availability).
- fulfillment-service and shipping-service make **no** synchronous calls; everything they need arrives in events.

### 6.4 Apache Kafka

**Where Kafka is used, and where it is not.** Checkout is not on Kafka: it needs an immediate answer and an ACID guarantee. Kafka connects everything *after* checkout and feeds tracking. The README should say this explicitly.

Topics (document in `docs/events.md`; create them on startup with `NewTopic` beans in the producing service: 3 partitions, replication 1 locally):

| Topic | Producer | Event types | Consumers |
|---|---|---|---|
| `order-events` | order-service | `ORDER_INITIATED`, `INVENTORY_RESERVED`, `PAYMENT_CAPTURED`, `ORDER_CONFIRMED`, `ORDER_REJECTED`, `ORDER_FAILED`, `PAYMENT_REFUNDED`, `INVENTORY_RESTORED`, `ORDER_CANCELLED`, `ORDER_DELIVERED`, `DELIVERY_ACKNOWLEDGED` | fulfillment-service (`ORDER_CONFIRMED` only), order-tracking-service (all) |
| `fulfillment-events` | fulfillment-service | `FULFILLMENT_RECEIVED`, `FULFILLMENT_PICKING`, `FULFILLMENT_PACKED`, `FULFILLMENT_FAILED` | shipping-service (`FULFILLMENT_PACKED`), order-service, order-tracking-service |
| `shipping-events` | shipping-service | `SHIPMENT_CREATED`, `SHIPMENT_PICKED_UP`, `SHIPMENT_IN_TRANSIT`, `SHIPMENT_OUT_FOR_DELIVERY`, `SHIPMENT_DELIVERED` | order-service, order-tracking-service |
| `<topic>.DLT` | error handler | failed records | inspected manually |

Event envelope (a record in each service, JSON): `eventId` (UUID), `eventType`, `orderId`, `userId` (the customer who owns the order; consumers use it for ownership checks), `occurredAt` (UTC instant), `source` (service name), `version` (int, start at 1), `payload` (event-specific object). For `inventory-events`, `orderId`/`userId` are null and the key is `productId` (see 6.12). **For all order-related topics the Kafka record key is always `orderId`**, so all events for one order land in the same partition, in order.

**Transactional outbox (every producing service):**
- Business change and `outbox_event` insert happen in the same local transaction. Nothing calls `KafkaTemplate` directly from business code.
- `OutboxRelay` is a `@Scheduled` poller (e.g. every 500 ms) that reads unpublished rows in `created_at` order with `SELECT … FOR UPDATE SKIP LOCKED LIMIT 100`, sends each with `KafkaTemplate.send(...).get(timeout)`, and sets `published_at`. On failure it increments `attempts` and stops the batch to keep ordering. Delivery is **at least once**.
- Producer config: `acks=all`, `enable.idempotence=true`.
- Comment in `OutboxRelay` why the outbox exists (it avoids the "dual write" problem: a DB commit with a lost Kafka send, or the reverse).

**Consumers:**
- `@KafkaListener` with a group id per service. Manual or record-level ack after the DB commit.
- **Idempotent:** in the same transaction as the handler's DB changes, insert the `eventId` into `processed_event`. If it's already there, skip the event.
- Error handling: `DefaultErrorHandler` with exponential backoff (3 attempts), then `DeadLetterPublishingRecoverer` to `<topic>.DLT`. Deserialization errors go straight to the DLT (`ErrorHandlingDeserializer`).
- JSON with explicit type mapping (`spring.json.type.mapping` or type headers mapped to each service's own classes), so consumers don't depend on the producer's class names. Unknown event types are logged and skipped, not failed.

**fulfillment-service:** on `ORDER_CONFIRMED` → create `fulfillments` (`RECEIVED`) + outbox `FULFILLMENT_RECEIVED`. A `@Scheduled` simulator advances `RECEIVED → PICKING → PACKED`, each step after a configurable delay (`demo.simulation.step-delay`), with a configurable failure rate (`demo.simulation.failure-rate`) that produces `FULFILLMENT_FAILED`. Every transition writes an outbox event.

**shipping-service:** on `FULFILLMENT_PACKED` → create a shipment (`LABEL_CREATED`, generated tracking number, address copied from the event) + `SHIPMENT_CREATED`. Simulator advances `PICKED_UP → IN_TRANSIT → OUT_FOR_DELIVERY → DELIVERED`, same delay setting. Exposes `GET /api/shipments/by-order/{orderId}` (owner or `ADMIN`; used by the aggregator) and, for admins, `GET /api/admin/shipments?status=&page=&size=` (paged, newest first) and `GET /api/admin/shipments/{trackingNumber}`.

**order-service as a consumer:** updates its own order status from `FULFILLMENT_RECEIVED` (`IN_FULFILLMENT`), `SHIPMENT_PICKED_UP` (`SHIPPED`), and `SHIPMENT_DELIVERED` (`DELIVERED`, plus `ORDER_DELIVERED` event). Status only moves forward; ignore an event that would move it backward. Handles `FULFILLMENT_FAILED` via compensation (6.6).

Kafka tests: Testcontainers Kafka + Awaitility, at least: outbox row → record on the topic; duplicate event delivered twice → handled once; poison message → lands on the DLT.

### 6.5 order-tracking-service (CQRS read model on DynamoDB)

- Consumes **all three topics** with its own consumer group, from the earliest offset for a new group.
- Storage is **DynamoDB** through `DynamoDbEnhancedClient` (table schema in section 4). No JPA, no Flyway, no Postgres in this module. Put DynamoDB access behind a `TrackingRepository` class in `persistence/`, so the rest of the service doesn't know about DynamoDB.
- **Writing an event** = one `TransactWriteItems` call with two actions:
  1. **Put** the `EVENT#…` item with condition `attribute_not_exists(PK)`. The key is built only from the event's own fields, so a redelivered event has the same key and the condition fails → this is the **idempotency check** (no `processed_event` table needed).
  2. **Update** the `STATE` item with condition `attribute_not_exists(statusRank) OR statusRank < :newRank`, setting `currentStatus`, `statusRank`, `lastEventAt`. This makes status **only move forward**, even when events from different topics arrive out of order.
  
  If the transaction is cancelled, inspect the cancellation reasons: a failed condition on (1) means duplicate → acknowledge and skip. A failed condition on (2) alone means the event is older than the current status → write the event item on its own (the timeline still records it) and acknowledge. Any other failure (throttling, network) → throw, so the Kafka error handler retries and eventually sends it to the DLT. Comment this logic carefully; it is the core of the service.
- **Reading:**
  - Access: a `CUSTOMER` may read only orders whose `STATE.userId` equals the JWT `sub` (return `404`, not `403`, for someone else's order, so ids can't be probed); `ADMIN` may read any order. Store `userId` on the `STATE` item from the event envelope.
  - `GET /api/tracking/orders/{orderId}` → one `Query` on `PK = ORDER#<id>`, which returns the `STATE` item and all `EVENT#` items in sort order. Map to current status + timeline (`[{status, source, occurredAt, details}]`). `404` if nothing found.
  - `GET /api/tracking/orders/{orderId}/latest` → `GetItem` on `STATE` (used by the aggregator). Use a strongly consistent read here and say why in a comment (read-your-writes right after checkout).
  - Optional: `GET /api/tracking/orders/{orderId}/stream` as Server-Sent Events, only if asked.
- There is no transaction spanning Kafka and DynamoDB. Correctness comes from **at-least-once delivery + idempotent conditional writes**. Acknowledge the Kafka record only after the DynamoDB write succeeds (or was a confirmed duplicate).
- Client config: explicit API-call and attempt timeouts on the SDK client, SDK retry policy left on (standard mode). `local` profile points `endpointOverride` at DynamoDB Local with dummy credentials; otherwise use the default AWS credentials/region chain. All of this lives in `@ConfigurationProperties` (`tracking.dynamodb.endpoint`, `table-name`, `region`).
- Observability: tracing continues from the Kafka listener span. Add a timer `tracking.dynamodb.write` (tagged `outcome` = written/duplicate/stale/error), and enable SDK metrics if it's straightforward. A custom Actuator `HealthIndicator` calls `DescribeTable`.
- No synchronous calls to other services; it knows only what events tell it. This is the point of the pattern. Mention in the README that the table could be rebuilt by replaying the topics (new consumer group from the earliest offset).
- Tests (DynamoDB Local in Testcontainers + Kafka Testcontainers + Awaitility):
  - repository: write → query returns events in time order;
  - the same event written twice → one item;
  - an older-status event after a newer one → timeline has both, `currentStatus` unchanged;
  - end to end: publish events for one order to the topics → `GET /api/tracking/orders/{id}` shows the full timeline.
- README section **"Why DynamoDB here (and not in order-service)"**: access pattern is always by order id, append-heavy writes, no joins or multi-entity transactions, rebuildable from Kafka. order-service stays on Postgres because checkout relies on relational constraints and `@Transactional`.

### 6.6 Saga (scaled down: one compensation)

This replaces the earlier orchestrated saga. The local transaction (6.1) removes the need for compensation during checkout. Only one cross-service failure remains:

- `FULFILLMENT_FAILED` (after payment was captured) → order-service runs `OrderCancellationService.cancel(orderId)` in **one `@Transactional`**: restore stock (positive `stock_movements`, `ORDER_CANCELLED` reason), credit the wallet (plus a `wallet_transactions` `REFUND` row), mark the payment `REFUNDED`, set the order `CANCELLED`, and write outbox events `INVENTORY_RESTORED`, `PAYMENT_REFUNDED`, `ORDER_CANCELLED`.
- It must be idempotent: a second `FULFILLMENT_FAILED` for the same order changes nothing.
- This is a **choreography** saga (services react to events; no central coordinator). Name it that way in the README, and contrast it with orchestration.
- Test: force the fulfillment failure rate to 100%, place an order, and assert with Awaitility that stock and wallet return to their original values and tracking shows the full timeline ending in `ORDER_CANCELLED`.

### 6.7 Discovery and routing
- `discovery-server`: Eureka server with `@EnableEurekaServer`, self-registration disabled.
- All other services are Eureka clients and register under their `spring.application.name`.
- Feign resolves service names through Spring Cloud LoadBalancer; the gateway uses `lb://service-name` URIs.
- Document in the README how to run two instances of `product-service` on different ports and watch Feign calls get load-balanced.

### 6.8 API gateway
- `api-gateway` (8080) sits **behind nginx**. In the `docker` profile its port is not published, so only nginx (and internal callers) can reach it. In `local`, port 8080 stays open for curl and tests, with tokens from `dev-idp/dev-token.sh`.
- Routes (in `application.yml`, not Java DSL, so they are easy to read):
  - `/api/users/me/**`, `/api/admin/me` → `lb://user-service`
  - `/api/products/**`, `/api/categories/**` → `lb://product-service`
  - `/api/storefront/**` → `lb://storefront-bff`
  - `/api/cart/**` → `lb://cart-service`
  - `/api/orders/**`, `/api/wallet/**` → `lb://order-service`
  - `/api/admin/orders/**`, `/api/admin/payments/**`, `/api/admin/inventory/**` → `lb://order-service`
  - `/api/admin/shipments/**` → `lb://shipping-service`
  - `/api/shipments/**` → `lb://shipping-service` (GET only)
  - `/api/tracking/**`, `/api/admin/tracking/**` → `lb://order-tracking-service`
  - fulfillment-service is **not** exposed (internal only). Note this as a deliberate choice in the README.
- Authentication and route-level authorization are described in 6.11.
- Global filters: correlation ID (generate `X-Correlation-Id` if missing, pass it downstream, return it in the response), request logging with trace id.
- Per-route circuit breaker with a fallback endpoint returning a `503` ProblemDetail, plus per-route `connect-timeout` / `response-timeout`.
- Rate limiting: in-memory first, keyed by the authenticated user (`sub`), falling back to client IP for anonymous requests (catalog, guest carts). Anonymous limits are lower than authenticated ones. (nginx also rate-limits per IP at the edge.) Redis-backed `RequestRateLimiter` only if asked.

### 6.9 Resilience (Resilience4j on Feign adapters)
Configure per downstream service in each caller's `application.yml`, with instance names matching the service (`userService`, `productService`, `shippingService`, `trackingService`, `cartService`):
- **Timeouts:** Feign connect/read timeouts per client (6.3), plus the `CompletableFuture` deadline (6.2). No call is unbounded. (`TimeLimiter` is not used with blocking Feign calls. Comment why.)
- **Retry** with exponential backoff and jitter, **only** for idempotent calls (all current Feign calls are GETs) and only on the retryable exception from the error decoders: never on 4xx.
- **Circuit breaker** per downstream service, with sliding-window settings and a half-open state.
- **Bulkhead** (semaphore) per downstream service so one slow dependency cannot use up the composition executor.
- **Fallbacks:** aggregator calls fall back to "section unavailable"; checkout lookups have **no** fallback (they must fail).
- Aspect order: Retry wraps CircuitBreaker wraps Bulkhead. Set the aspect order explicitly in config, and comment it.
- Kafka consumers are protected by the error handler + DLT (6.4), not Resilience4j.
- user-service and product-service expose **chaos toggles**, active only in the `local` profile: `demo.chaos.latency-ms` and `demo.chaos.failure-rate`, changeable at runtime through a small `POST /internal/chaos` endpoint (not routed by the gateway).
- Expose Resilience4j metrics and circuit-breaker state through Actuator.

### 6.10 Observability
- Every service: Actuator with `health`, `info`, `metrics`, `prometheus` exposed (nothing else outside `local`).
- Tracing: Micrometer Tracing + OpenTelemetry bridge, OTLP to Jaeger, 100% sampling in `local`.
  - HTTP: gateway → services, and Feign calls (via `feign-micrometer`), propagate automatically.
  - Kafka: enable `spring.kafka.template.observation-enabled=true` and `spring.kafka.listener.observation-enabled=true`.
  - Outbox: store the current W3C `traceparent` in `outbox_event.trace_parent` when the row is written. When the relay publishes, continue that trace, so one order can be followed in Jaeger from the HTTP request through Kafka to fulfillment, shipping, and tracking. If this turns out to be fiddly, document the gap honestly rather than faking it.
  - `CompletableFuture` workers keep the trace through the `ContextPropagatingTaskDecorator` (6.2).
- Logging: `traceId`, `spanId`, `correlationId`, and (where known) `orderId` in every log line via MDC. Readable text logs in `local`, structured JSON (`logging.structured.format.console=ecs`) elsewhere.
- Custom metrics:
  - order-service: `checkout.attempts` (counter tagged `outcome` = confirmed/out_of_stock/insufficient_funds/…), `checkout.duration` (timer), `composition.duration` (timer tagged `degraded`), `order.cancellations` (counter).
  - every producing service: `outbox.pending` (gauge of unpublished rows) and `outbox.publish.failures` (counter).
  - Kafka consumer lag via the built-in Spring Kafka / Micrometer metrics.
- Health: readiness includes the DB (Postgres, or the DynamoDB table check in order-tracking-service) and the Kafka admin for Kafka services. Circuit-breaker state is visible but must not mark a service DOWN.
- Prometheus scrapes all services. Provision one Grafana dashboard: request rate, p95/p99 latency, error rate, circuit-breaker state, checkout outcomes, outbox pending, consumer lag.

### 6.11 Security: federated login (Google, Okta), internal JWTs, roles

**Who can do what:**

- **Anyone (not signed in):** browse categories and products, use a guest cart (6.12).
- **`CUSTOMER`:** signs in with **Google**; the first sign-in **registers** them (just-in-time). Places orders (paying from their wallet), views and tracks **their own** orders, shipments and payments, tops up their wallet, manages their address, acknowledges delivery.
- **`ADMIN`:** signs in with **Okta** and must be in the Okta group **`smd-admins`**. Restocks products; views and tracks **all** orders, shipments/deliveries and payments. Admins don't place orders.
- **Dev only:** the two seeded `LOCAL` sample users sign in through the **dev identity provider** (`dev-idp/`, a mock OIDC server standing in for Google and Okta). It's the same path as real users (nginx → oauth2-proxy → gateway → token exchange), or for API tests a token from `dev-idp/dev-token.sh`. `local` profile only.

**Three layers, each enforcing on its own:**

```
Browser ─cookie─► nginx + oauth2-proxy ─Bearer Google ID token / Okta access token─► api-gateway ─internal JWT─► services
          (edge: who is signed in,          (validates the external token, exchanges         (roles + ownership,
           which paths need which login)     it for an internal JWT, route role rules)        trust only internal JWTs)
```

**Layer 1: edge (`nginx-proxy/`, already written and tested; see its README):**

- nginx serves the UI and decides per path: public catalog; cart with an optional customer session; customer paths need a Google session; `/admin` and `/api/admin/**` need an Okta session; everything else under `/api` is blocked.
- Two oauth2-proxy instances run the OIDC flows (PKCE, Redis session store). The browser only holds HttpOnly cookies `_smd_customer` / `_smd_admin`, never a token.
- For each request nginx forwards `Authorization: Bearer <token>` taken from the session: the Google **ID token** for customers, the Okta **access token** for admins. Any client-sent `Authorization` on public paths is removed, and spoofed `X-Auth-Request-*` headers are stripped.
- **CSRF:** sessions are cookies, so nginx rejects state-changing API calls without `X-Requested-With: XMLHttpRequest`. Cookies are `SameSite=Lax`.
- **Keep nginx in sync with the gateway:** when an API path is added or moved, update `nginx-proxy/nginx/templates/default.conf.template`, the table in `nginx-proxy/README.md`, and the gateway rules below in the same change.

**Layer 2: gateway (`api-gateway`, Spring Security WebFlux):**

- Multi-issuer resource server: `JwtIssuerReactiveAuthenticationManagerResolver` trusting **exactly** these issuers, each with its own decoder validating signature (issuer JWKS), `iss`, `aud`, and `exp` (60 s skew):

| Issuer | `iss` | Audience | Extra checks | Role granted |
|---|---|---|---|---|
| Google | `https://accounts.google.com` | the Google client id (`security.google.client-id`) | `email_verified == true` | `CUSTOMER` |
| Okta | `https://${OKTA_DOMAIN}/oauth2/default` | `api://default` | `groups` contains `security.okta.admin-group` (`smd-admins`), else **no role** (→ 403) | `ADMIN` |
| dev-customer (dev-idp) | `http://dev-idp:8080/dev-customer` **and** `http://localhost:8099/dev-customer` | `dev-client` | **`local` profile only**; `email_verified == true` | `CUSTOMER` |
| dev-admin (dev-idp) | `http://dev-idp:8080/dev-admin` **and** `http://localhost:8099/dev-admin` | `api://default` | **`local` profile only**; `groups` contains `smd-admins`, else no role | `ADMIN` |

- **Dev issuers** come from `security.dev-issuers` in `application-local.yml`, as a list of `{issuer, jwk-set-uri, audience, role, required-group}`. They're needed because the token's issuer and the address for fetching keys differ: the browser and `dev-token.sh` reach dev-idp on `localhost:8099`, while oauth2-proxy reaches it as `dev-idp:8080`. Each dev issuer is listed under both addresses, with `jwk-set-uri: http://localhost:8099/<issuer>/jwks` when the gateway runs on your machine. See `dev-idp/README.md`. **Startup must fail if `security.dev-issuers` is set outside the `local` profile**, and the gateway never trusts internal (`smd-internal`) tokens from outside.
- **Token exchange** (`TokenExchangeFilter`, a `GlobalFilter` that runs after authentication, before routing): for a Google, Okta or dev-idp principal, call user-service `POST /internal/auth/exchange` with the external token. This uses a `@LoadBalanced WebClient`, the gateway's only HTTP client (it is reactive). The response is an **internal JWT**, which replaces the `Authorization` header. Cache it in Caffeine, keyed by the SHA-256 of the external token, until 30 s before the earlier of the two expiries, so normal browsing costs one exchange per few minutes, not one per request. user-service down → `503` ProblemDetail; user suspended → `403`. **Services never see Google or Okta tokens.**
- Stateless, no sessions, HTTP Basic/form login off. CSRF is off **at the gateway** (it only sees bearer tokens), with a comment that CSRF is enforced at nginx. No CORS needed (same origin through nginx); allow `http://localhost:4200` only in `local`.
- `401`/`403` as RFC 7807 ProblemDetail JSON (custom entry point and access-denied handler).
- On public routes, a bearer token that *is* present must be valid (`401` otherwise).
- Route rules (most specific first; anything not listed is **denied**):

| Path | Method | Access |
|---|---|---|
| `/actuator/health` | GET | public |
| `/api/products/**`, `/api/categories/**`, `/api/storefront/**` | GET | public |
| `/api/cart/**` | GET, POST, PUT, DELETE | public at the gateway; cart-service decides (guest by `X-Cart-Id`, customer by JWT) |
| `/api/admin/**` | any | `ADMIN` |
| `/api/orders/**`, `/api/wallet/**`, `/api/tracking/**`, `/api/shipments/**` | as per endpoint | `CUSTOMER` (ownership checked in the service) |
| `/api/users/me/**` | GET, PUT | `CUSTOMER` |

**user-service (identity):**

- `POST /internal/auth/exchange` (internal only, not routed by the gateway, blocked at nginx): user-service **validates the external token itself** with the same issuer rules as the gateway, including `security.dev-issuers` in `local`. So nobody can get an internal token without a valid token from a trusted issuer. Then:
  1. Find the user by `(auth_provider, external_subject)`. If none: **create** it (Google → `CUSTOMER`, Okta → `ADMIN`; Okta without the admin group → `403`). This is the "register with Google" step. Concurrent first logins race on the unique constraint: catch the violation and re-read. **Dev issuers never create users:** they map to the seeded `LOCAL` user whose `external_subject` equals the token's `sub` (`sample-customer`, `sample-admin`). An unknown dev subject → `403`. `sample-admin` additionally needs `smd-admins` in `groups`.
  2. Refresh `email` (lower-case) and `full_name` from the token, and set `last_login_at`. `SUSPENDED` → `403`.
  3. Return an internal JWT: RS256, **5 minutes**, `iss = smd-internal`, `aud = smd-api`, `sub` = internal user id, `email`, `name`, `roles`, `idp` (`google` / `okta` / `dev`), `jti`.
- `GET /.well-known/jwks.json`: the internal public key (internal only).
- `GET /api/users/me`, `PUT /api/users/me/address` (customers; a new Google customer has no address until they add one), `GET /api/admin/me` (admins).
- Keys: `scripts/generate-dev-keys.sh` creates an RSA 2048 key pair in `.local/keys/` (`.local/` is git-ignored). user-service reads it from `auth.jwt.private-key-location`. **Never commit a private key.** Tests generate keys in memory.

**Layer 3: services (defense in depth):**

- Every service except discovery-server and the gateway is a servlet **OAuth2 resource server** (`security-conventions.gradle`) trusting **only** the internal issuer: `smd-internal`, `aud = smd-api`, JWKS fetched by service name through a `@LoadBalanced` client (`http://user-service/.well-known/jwks.json`). `@EnableMethodSecurity`, stateless, `roles` claim → `ROLE_` authorities. Each service has its own small `SecurityConfig` (no shared library).
- Admin controllers (`/api/admin/**`) use `@PreAuthorize("hasRole('ADMIN')")`. Customer controllers use `hasRole('CUSTOMER')` and filter by `user_id = sub` via a `CurrentUser` helper. Someone else's resource returns `404`, never `403`. Service-internal endpoints that other services call with a relayed token (e.g. tracking `latest`, user `GET /api/users/{id}`) allow the owner **or** `ADMIN`.
- Permitted without a token: `/actuator/health`, `/actuator/prometheus`, user-service `/.well-known/jwks.json` and `/internal/auth/exchange` (it validates the external token itself). `/internal/chaos` only in `local`.
- Kafka consumers run without a user; ownership travels in the event envelope (`userId`).

**Endpoints by role:**

| Service | Endpoint | Role | Notes |
|---|---|---|---|
| user-service | `POST /internal/auth/exchange` | internal (gateway) | JIT registration (Google/Okta), sample-user mapping (dev-idp), internal JWT |
| user-service | `GET /api/users/me`, `PUT /api/users/me/address` | `CUSTOMER` | profile + default address |
| user-service | `GET /api/admin/me` | `ADMIN` | admin profile for the admin UI |
| user-service | `GET /api/users/{id}` | owner or `ADMIN` | internal, via Feign with the relayed token |
| product-service | `GET /api/products…`, `GET /api/categories` | public | stock *levels* only (6.12) |
| order-service | `POST /api/orders/checkout`, `POST /api/orders` | `CUSTOMER` | checkout = payment (6.1) |
| order-service | `GET /api/orders`, `GET /api/orders/{id}`, `GET /api/orders/{id}/details` | `CUSTOMER` (own) | list is paged |
| order-service | `POST /api/orders/{id}/acknowledge-delivery` | `CUSTOMER` (own) | see below |
| order-service | `GET /api/wallet`, `POST /api/wallet/top-ups` | `CUSTOMER` | top-up: `{amount}` ≤ 1000.00, `Idempotency-Key` required; `@Transactional` balance + `wallet_transactions` |
| order-service | `GET /api/admin/orders?status=&userId=&from=&to=&page=&size=` | `ADMIN` | all orders, paged |
| order-service | `GET /api/admin/orders/{id}`, `GET /api/admin/orders/{id}/details` | `ADMIN` | any order; the details aggregator (6.2) |
| order-service | `GET /api/admin/payments?status=&userId=&page=&size=` | `ADMIN` | all payments, with totals by status |
| order-service | `GET /api/admin/inventory` | `ADMIN` | exact stock per product (names via product-service, in parallel) |
| order-service | `POST /api/admin/inventory/{productId}/restock` | `ADMIN` | `{quantity > 0, note}`; `@Transactional`: increment + `stock_movements` (`RESTOCK`, `performed_by`) + `INVENTORY_CHANGED` outbox |
| shipping-service | `GET /api/shipments/by-order/{orderId}` | `CUSTOMER` (own), `ADMIN` via relayed calls | |
| shipping-service | `GET /api/admin/shipments?status=&page=&size=`, `GET /api/admin/shipments/{trackingNumber}` | `ADMIN` | all shipments/deliveries |
| order-tracking-service | `GET /api/tracking/orders/{orderId}[/latest]` | `CUSTOMER` (own), `ADMIN` via relayed calls | 6.5 |
| order-tracking-service | `GET /api/admin/tracking/orders/{orderId}` | `ADMIN` | timeline of any order |

"Track all orders" for admins = `GET /api/admin/orders` (listing, from Postgres) + `GET /api/admin/tracking/orders/{id}` (timeline). Do **not** add a DynamoDB `Scan` to list orders; comment why in the tracking service.

**Acknowledge delivery** (`POST /api/orders/{id}/acknowledge-delivery`, `@Transactional` in order-service): only the owner, only when `DELIVERED` (else `409`). Sets `COMPLETED` + `delivery_acknowledged_at` and writes a `DELIVERY_ACKNOWLEDGED` outbox event, so the tracking timeline ends with the customer's confirmation. A second call returns the same result without changing anything. `COMPLETED` is the highest status rank in tracking.

**Configuration** (env vars, local defaults only where harmless):

- Gateway and user-service: `security.google.client-id` (`GOOGLE_CLIENT_ID`), `security.okta.issuer-uri` (`https://${OKTA_DOMAIN}/oauth2/default`), `security.okta.audience` (`api://default`), `security.okta.admin-group` (`smd-admins`).
- Gateway and user-service, `local` only: `security.dev-issuers` (see above and `dev-idp/README.md`).
- nginx-proxy: `nginx-proxy/.env` (see `.env.example`). The dev-idp override needs no `.env`.

If Google/Okta aren't configured, the `local` profile still works end to end, **through nginx and the frontend**, with the dev identity provider: `cd nginx-proxy && docker compose -f docker-compose.yml -f ../dev-idp/docker-compose.dev-idp.yml --profile google --profile okta --profile dev-idp up -d`, then sign in as `sample-customer` / `sample-admin`.

**Security tests (required):**

- Gateway: use WireMock to serve JWKS for a fake "Google" and a fake "Okta" issuer, signing test tokens with keys generated in the test. Check:
  - Google token → `CUSTOMER`;
  - Google token with the wrong `aud` or `email_verified=false` → `401`;
  - Okta token with `smd-admins` → `ADMIN`;
  - Okta token without the group → `403` on `/api/admin/**`;
  - a token from an unknown issuer → `401`;
  - dev-issuer tokens: `sample-customer` → `CUSTOMER`, `sample-admin` → `ADMIN`, and `sample-not-admin` → `403` on `/api/admin/**`; all of them → `401` outside the `local` profile;
  - a startup with `security.dev-issuers` set outside `local` fails;
  - an internal `smd-internal` token sent from outside → `401`;
  - every row of the route table;
  - the exchange filter swaps the header and is called **once** for N requests with the same token (cache);
  - a suspended user → `403`.
- user-service exchange:
  - the first Google login creates exactly one `CUSTOMER`, and concurrent first logins also end with one row;
  - an Okta login creates an `ADMIN`; an Okta login without the group → `403`;
  - a token with a forged signature → `401`;
  - dev-idp tokens map to the seeded users (ids `…c1` / `…a1`); an unknown dev subject → `403`, and no row is created; dev-issuer tokens are rejected outside `local`.
- Ownership: customer A can't read customer B's order, details, shipment, tracking or wallet (`404`); admin endpoints can read all of them; a customer token on `/api/admin/**` → `403`; an admin token on `POST /api/orders/checkout` → `403`.
- Manual end-to-end check with dev-idp (browser and `dev-token.sh`), following `dev-idp/README.md`. That includes the three "still to verify" items listed there. Record the result in that README.
- The edge was verified while writing `nginx-proxy/`. After any change to the nginx template, re-run `nginx -t` and a routing smoke test (the approach is described in `nginx-proxy/README.md`).

Document the role matrix, both identity providers, the token exchange, and the sample credentials in `docs/security.md`.

### 6.12 Online storefront: public catalog, cart, checkout from cart

Goal: anyone can browse products on the home page without logging in, add products to a cart as a guest, log in, and check out.

**Public catalog (product-service):**
- Endpoints (public): `GET /api/categories`, `GET /api/products?category=&q=&featured=&page=&size=&sort=` (paged; `q` is a simple case-insensitive name/description match), `GET /api/products/{idOrSlug}`, `GET /api/products?ids=…` (batch, used by Feign callers).
- Every product response includes `availability` (`IN_STOCK`, `LOW_STOCK`, `OUT_OF_STOCK`) from `product_availability`, **never the exact stock count**. Admin stock numbers stay in order-service's admin endpoints.
- **Caching:** Spring Cache with Caffeine (caches `categories`, `productPages`, `productById`, `featured`; short TTL, e.g. 60 s, and bounded size). Evict the affected entries when an availability update is applied. Add `Cache-Control: public, max-age=60` and ETags (`ShallowEtagHeaderFilter` on catalog GETs) to anonymous responses only. Never cache a response that depended on the caller's identity.
- Anonymous product traffic must never reach order-service.

**Stock levels via Kafka (CQRS, like tracking):**
- New topic `inventory-events` (key = `productId`, 3 partitions). order-service writes an `INVENTORY_CHANGED` outbox row (`{productId, quantityOnHand}`) in the **same transaction** as every stock change: checkout (6.1), cancellation (6.6), and admin restock (6.11).
- product-service consumes it, maps quantity to a level (`OUT_OF_STOCK` = 0, `LOW_STOCK` ≤ `storefront.low-stock-threshold`, default 5, otherwise `IN_STOCK`), and upserts `product_availability`. It is idempotent via `processed_event` and ignores events older than `last_event_at`.
- Comment the trade-off in code and README: availability is **eventually consistent** (it can lag by a moment). The authoritative check is the checkout transaction, so the worst case is "shown in stock, rejected at checkout with `409`".
- Add `inventory-events` to `docs/events.md`. order-tracking-service does **not** consume it.

**cart-service (new):**
- Spring Boot servlet service, DynamoDB `carts` table (section 4), Eureka client, resource server (6.11) that also allows anonymous requests. At the edge, nginx forwards the customer's token when a Google session exists and no token otherwise (`nginx-proxy`).
- **Guest carts:** `POST /api/cart` with no token creates a guest cart and returns it with its `cartId` (UUID v4) in the body and the `X-Cart-Id` response header. The frontend stores the id and sends `X-Cart-Id` on later cart calls. The id is an unguessable bearer secret: never log it in full, and never return a guest cart to a request without the right id.
- **Why a header and not a cookie:** the cart id is a bearer secret for guests; as a cookie the browser would send it automatically (CSRF exposure, and it would leak to every path). Comment this in cart-service. Cart mutations still need `X-Requested-With` at nginx, like every state-changing call.
- **Customer carts:** with a valid `CUSTOMER` JWT, `GET /api/cart` returns (or lazily creates) the customer's single cart, looked up by `byOwner`. `X-Cart-Id` is ignored for an authenticated customer unless it's in a merge call. Admins get `403` on cart endpoints.
- Endpoints: `GET /api/cart`, `POST /api/cart` (create guest cart), `POST /api/cart/items` `{productId, quantity}` (adds to an existing line), `PUT /api/cart/items/{productId}` `{quantity}` (0 removes it), `DELETE /api/cart/items/{productId}`, `DELETE /api/cart`.
- **Merge on login:** `POST /api/cart/merge` (requires `CUSTOMER` JWT + `X-Cart-Id` of the guest cart). Add the guest lines into the customer's cart (summing quantities, capped by the per-product limit), then delete the guest cart. It must be idempotent: merging an already-merged or missing guest cart returns the customer cart unchanged. The frontend calls this right after login.
- **Cart view:** `GET /api/cart` returns lines with the **current** name, image, unit price, availability, and line totals, fetched from product-service in one batch Feign call (`ids=`), plus a cart subtotal. If product-service is down, return the lines with `"degraded": true` and no prices, never a 500. Stored items hold only `productId` + `quantity`; prices are never stored in the cart, so they can't go stale.
- Adding to the cart does **not** reserve stock. Adding an `OUT_OF_STOCK` product returns `409`; otherwise it's allowed.
- **Clearing after checkout:** cart-service consumes `order-events` (`ORDER_CONFIRMED` with a `cartId`) and removes the ordered quantities from that cart, deleting the cart when it becomes empty. It is idempotent via a small `processedEvents` set on the cart item, or a separate DynamoDB item keyed `EVENT#<eventId>` with a TTL. Items added after checkout started are kept. The cart is **not** cleared synchronously from order-service. Comment why: no remote calls in the checkout transaction, and a failed checkout must leave the cart untouched.

**Checkout from the cart (order-service):**
- `POST /api/orders/checkout` (role `CUSTOMER`, empty body). order-service reads the customer's cart from cart-service via Feign (relaying the JWT), rejects an empty cart (`422`), then runs **exactly the same** flow as 6.1 with the cart lines as items and `cart_id` stored on the order.
- Idempotency: if the client sends no `Idempotency-Key`, derive it from `cartId + cart version`, so a double-click can't create two orders from the same cart state.
- `POST /api/orders` with explicit items stays for API clients and tests.

**storefront-bff (new, home-page aggregator):**
- `GET /api/storefront/home` (public) returns `{categories, featured, newArrivals}`. These come from **parallel** Feign calls to product-service using the same `CompletableFuture` + bounded executor pattern as 6.2. If one section fails, return the others with `degraded: true`.
- `GET /api/storefront/products/{slug}` (public): product details plus a few products from the same category, in parallel.
- No database, no business rules: it shapes data for the UI. Short Caffeine cache (30 s) on the home response for anonymous callers only. Explain in the README how a BFF differs from the gateway (the gateway routes and secures; the BFF composes for one client).

**Tests (required):**
- Catalog endpoints work with **no token**. A request with an invalid token is `401`. Responses never contain exact stock numbers.
- Availability: an `INVENTORY_CHANGED` event to 0 → product shows `OUT_OF_STOCK` (Awaitility); duplicate and out-of-order events are ignored.
- Cart (DynamoDB Local): guest create/add/update/remove; wrong or missing `X-Cart-Id` cannot read a guest cart; merge sums quantities and deletes the guest cart; merging twice is safe; concurrent updates hit the version condition; TTL attribute is set.
- Checkout from cart: happy path → order confirmed and, after `ORDER_CONFIRMED`, the cart is emptied; out-of-stock at checkout → `409` and the cart is unchanged; the same cart version checked out twice → one order.
- BFF: home page with product-service slowed by the chaos toggle shows parallel timing; product-service down → degraded response.

**Frontend (`frontend/`, Angular):**
- Scaffold with the Angular CLI inside `frontend/` (project name **`storefront`**, so the build lands in `frontend/dist/storefront/browser`, which nginx serves). Standalone components, routing, strict TypeScript, SCSS.
- **Day-to-day dev:** `npx ng build --watch` + the nginx stack, at http://localhost (logins work). For pure UI work, `npm start` (4200) with `proxy.conf.json` forwarding `/api` and `/oauth2` to `http://localhost` (nginx) also works: cookies are per host, not per port.
- Pages:
  - **Home:** categories, featured products, new arrivals (from `/api/storefront/home`).
  - **Category / search:** paged product grid.
  - **Product detail:** availability badge and "Add to cart".
  - **Cart:** quantities, remove, subtotal.
  - **Sign in with Google** button.
  - **Checkout:** shipping address (add one if missing), wallet balance, top-up, place order.
  - **My orders:** list, details, tracking timeline, "Confirm delivery".
  - **Admin area under `/admin`:** orders, payments, shipments, inventory with restock. nginx serves `/admin` pages only to Okta-authenticated admins.
- **Auth in the browser:** the app holds **no tokens**; the session is an HttpOnly cookie set by oauth2-proxy.
  - "Am I signed in?" = `GET /api/users/me` (admin UI: `GET /api/admin/me`).
  - Sign in: `window.location = '/oauth2/customer/start?rd=' + encodeURIComponent(currentUrl)`. Admins: `/oauth2/admin/start?rd=/admin`.
  - Sign out: `/oauth2/customer/sign_out?rd=/`, `/oauth2/admin/sign_out?rd=/`.
- HTTP interceptor:
  - adds `X-Requested-With: XMLHttpRequest` to **every** API call (nginx's CSRF rule) and `X-Cart-Id` when a guest cart exists;
  - on a `401` ProblemDetail with `loginUrl`, redirects to that URL with `rd` = the current page.
- After sign-in (app start with a session **and** a guest `cartId` in `localStorage`): call `POST /api/cart/merge`, then forget the guest id.
- Route guards (`customerGuard`, `adminGuard`) are convenience only; nginx, the gateway and the services enforce everything.
- Show `degraded` responses gracefully (e.g. "prices temporarily unavailable"), and surface ProblemDetail `title`/`detail` in error messages.
- Tests: unit tests for services, guards, and the interceptor (Angular's default test runner), plus one Playwright end-to-end test of browse → guest cart → (stubbed) sign-in → merge → checkout, if time allows.
- `frontend/README.md` explains how to run it. Do not add the frontend to the Gradle build; keep `node_modules/` and build output out of git. Product placeholder SVGs go in `frontend/public/products/<slug>.svg` (slugs in `data-model/README.md`).

## 7. Local environment

`docker/docker-compose.yml` runs the infrastructure:
- PostgreSQL 16 (5432)
- DynamoDB Local (`amazon/dynamodb-local`, 8000), run with `-sharedDb` and a mounted data directory so data survives restarts. Optionally `aaronshaf/dynamodb-admin` (8001) as a UI.
- Kafka, single broker in KRaft mode (official `apache/kafka` image), 9092
- Kafka UI (8090), e.g. `kafbat/kafka-ui`, for browsing topics and DLTs
- Jaeger all-in-one with OTLP enabled (UI 16686)
- Prometheus (9090), Grafana (3000)

Optionally add compose profiles to also run the services as containers (images built with `./gradlew bootBuildImage`).

The **edge stack** is separate: `nginx-proxy/docker-compose.yml` (nginx on **port 80**, oauth2-proxy for Google and Okta behind compose profiles, Redis). Open the app at **http://localhost**. Its secrets go in `nginx-proxy/.env` (from `.env.example`, git-ignored). Setup: Google steps in `nginx-proxy/README.md`, Okta steps in `okta-login-setup/README.md`. **Without** Google/Okta accounts, add the `dev-idp/` override (mock OIDC server on **port 8099**; see `dev-idp/README.md`).

Before the first run: `./scripts/generate-dev-keys.sh`.

Profiles: `local` (seed data, chaos toggles, fast simulation delays, 100% sampling, readable logs) and `docker` (container hostnames). No secrets in committed files; use environment variables with local-only defaults.

## 8. Commands

```bash
./gradlew build                         # compile + all tests
./gradlew :order-service:test           # one module's tests
./gradlew :order-service:bootRun --args='--spring.profiles.active=local'
docker compose -f docker/docker-compose.yml up -d
```

Start order: infrastructure (compose) → discovery-server → user, product, fulfillment, shipping, tracking, cart services → order-service → storefront-bff → api-gateway → UI build (`cd frontend && npx ng build --watch`) → edge (`cd nginx-proxy && docker compose --profile google --profile okta up -d`, or with `-f ../dev-idp/docker-compose.dev-idp.yml --profile dev-idp` for the sample users) → http://localhost.

## 9. Implementation phases

Security arrives in phase 11. Until then, services run with security off, and the user id for checkout comes from a temporary `X-Demo-User-Id` header that is **deleted in phase 11** (leave a `TODO(phase-11)` comment wherever it is used). The `users` table already has its identity columns from phase 2 (copied from `data-model/`).

Work in this order. Finish and verify each phase (build passes, tests green) before starting the next. Commit at the end of each phase with a clear message.

1. **Build skeleton:** Gradle 8.14 wrapper, `settings.gradle`, version catalog, Groovy `build-logic` convention plugins with the Azul 17 toolchain, and `build.gradle` + an empty application class in each of the ten existing service folders under `platform/`, `experience/` and `domains/` (keep their `README.md`), plus the flat project-name → folder mapping in `settings.gradle`. The Angular app comes in phase 14. Verify `./gradlew build` passes.
2. **SQL + infrastructure:** copy the reviewed scripts from `data-model/` into each service's Flyway folders (section 4; don't redesign), mount the init script, docker-compose (Postgres, DynamoDB Local, Kafka, Kafka UI, Jaeger, Prometheus, Grafana). Verify migrations apply against Testcontainers, and `data-model/scripts/verify-sql.sh` still passes.
3. **Discovery + user-service + product-service:** endpoints, JPA, chaos toggles.
4. **order-service checkout:** Feign clients + adapters, parallel pre-lookups, the `@Transactional` checkout, rejection handling, idempotency, and all 6.1 tests including concurrency.
5. **Resilience on Feign:** Resilience4j config, error decoders, WireMock tests (6.3, 6.9).
6. **Kafka foundation:** outbox tables + relay in order-service, topic creation, `docs/events.md`, and order-tracking-service consuming `order-events` into DynamoDB (table initializer, `TrackingRepository`, conditional transactional writes). Verify that a rejected order and a confirmed order both show up in tracking.
7. **fulfillment-service + shipping-service:** consumers, simulators, outbox, DLTs. Tracking consumes all topics. order-service status updates.
8. **Compensation:** fulfillment failure → refund + restock (6.6), with the end-to-end test.
9. **Aggregator endpoint:** `GET /api/orders/{id}/details` with `CompletableFuture` fan-out and degraded responses (6.2).
10. **API gateway:** routes, correlation-ID filter, circuit breaker fallback, timeouts, rate limiting.
11. **Security (internal side):**
    - key script; internal JWT issuing + JWKS; `/internal/auth/exchange` with JIT registration and dev-idp → sample-user mapping; `security.dev-issuers` (`local` only, with the startup guard);
    - gateway multi-issuer validation + token exchange filter + route rules, tested with fake Google/Okta issuers and checked manually with `dev-idp/`;
    - resource-server config and ownership checks in every service; JWT relay in Feign (including across `CompletableFuture` threads);
    - admin endpoints, wallet top-up, address endpoint, acknowledge delivery;
    - all 6.11 tests.
12. **Storefront + cart (backend):** categories/product fields, public catalog + caching, `inventory-events` + `product_availability`, cart-service (guest carts, merge, view, clearing via Kafka), checkout from cart, storefront-bff, gateway route/security updates, and all 6.12 backend tests.
13. **Edge + sign-in:** first bring up `nginx-proxy/` with the `dev-idp/` override and verify the whole path with the sample users: browser sign-in as customer and admin, the `sample-not-admin` refusal, `dev-token.sh` calls, rate limiting, CSRF, blocked paths. Resolve the "still to verify" items in `dev-idp/README.md`. Then switch to real sign-in. Ask the user to create the Google client and the Okta app (guides in `nginx-proxy/README.md` and `okta-login-setup/README.md`); **never** ask them to paste secrets into the chat, they go in `nginx-proxy/.env`. Verify end to end: anonymous browsing, Google sign-in registers a customer, Okta admin reaches `/admin`, a non-admin Okta user is refused, CSRF and blocked paths. Fix any drift between nginx, gateway and docs.
14. **Frontend:** Angular app in `frontend/` per 6.12 (confirm the Angular version with the user first): storefront pages, cart, sign-in + merge, checkout, my orders + tracking + confirm delivery, admin screens under `/admin`.
15. **Observability:** tracing across HTTP and Kafka (including outbox trace continuation), Prometheus + Grafana, structured logs, custom metrics.
16. **README:** architecture diagram (Mermaid), the order flow, a "patterns tour" pointing to the exact classes/config for each pattern, the inventory/payment-in-one-database trade-off, why tracking uses DynamoDB, the security design (nginx edge + Google/Okta + token exchange + defense in depth) with the sample users via `dev-idp/`, the storefront flow (guest cart → login → merge → checkout) and eventual consistency of availability, and demo scripts (curl) for: happy path through delivery, out of stock (rollback proof), insufficient funds, concurrent last-unit orders, slow product-service → parallel vs. sequential timing, circuit breaker opening, fulfillment failure → refund + restock, and following one order in Jaeger and in the tracking timeline.

**Later / only when asked:** Kafka transactions / exactly-once processing; Debezium CDC instead of the polling relay; Server-Sent Events for tracking; guest checkout without an account; Okta SCIM provisioning; social logins beyond Google; real card payments (payment intents); full-text search (OpenSearch); Keycloak or Spring Authorization Server as a self-hosted alternative to Google/Okta; Avro + Schema Registry; k6 or Gatling load tests; Redis rate limiting; Spring Cloud Config; Kubernetes manifests.

## 10. Coding and testing standards

- Constructor injection only; no field `@Autowired`.
- Records for DTOs, events, and value objects; validate requests with Jakarta Bean Validation.
- Errors return RFC 7807 `ProblemDetail` with a stable `type` URI per error (e.g. `/problems/out-of-stock`).
- Use `@ConfigurationProperties` (records) for custom configuration, not scattered `@Value`.
- Use `Clock` injection for time, so simulators and tests are deterministic.
- Tests: Testcontainers for anything touching the database or Kafka; WireMock for Feign clients; `@WebMvcTest` slices for controllers; Awaitility (never `Thread.sleep`) for async assertions. No test depends on Docker Compose being up.
- Keep comments for the *why* (transaction boundaries, lock ordering, outbox, aspect order, Java 17 constraints), not the *what*.
- Before saying a phase is done: run `./gradlew build` and report the result honestly. If something is stubbed or skipped, say so.

## 11. Working agreements for Claude

- Ask before changing anything in section 2 (stack and versions), adding a new module, or adding a dependency not implied by this file.
- Do not delete or rewrite migrations that have been committed; add a new `V<n>__...sql` instead.
- Changing an event's shape means updating `docs/events.md` first and bumping the envelope `version` if the change isn't backward compatible.
- `data-model/` must always match the migrations: schema changes update both in the same commit.
- Routes are defined in three places: the nginx template, the gateway, and the service controllers. When one changes, update all three, plus `nginx-proxy/README.md` and `docs/security.md`.
- Never put real secrets (Google/Okta client secrets, cookie secrets, private keys) in the repository or the chat. Use `.env` files (git-ignored) or environment variables.
- Keep the folder grouping (section 3). Don't move services between groups without asking.
- Keep changes scoped to the current phase. Note follow-ups in the README "Next steps" section rather than doing them unasked.
