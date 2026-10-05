# Sequence diagrams

The main flows, step by step. Each diagram names the classes that do the work, so you can jump from the picture to the code. For the static view (services, data, layers), see [architecture.md](architecture.md).

1. [Sign-in and token exchange](#1-sign-in-and-token-exchange)
2. [Guest cart → sign-in → merge](#2-guest-cart--sign-in--merge)
3. [Checkout: success](#3-checkout-success)
4. [Checkout: rejection and rollback](#4-checkout-rejection-and-rollback)
5. [After checkout: events through delivery](#5-after-checkout-events-through-delivery)
6. [Transactional outbox and trace continuation](#6-transactional-outbox-and-trace-continuation)
7. [Tracking write: idempotent and forward-only](#7-tracking-write-idempotent-and-forward-only)
8. [Compensation saga: fulfillment fails](#8-compensation-saga-fulfillment-fails)
9. [Order details aggregator (parallel fan-out)](#9-order-details-aggregator-parallel-fan-out)
10. [Admin restock → storefront availability](#10-admin-restock--storefront-availability)
11. [Delivery acknowledgement](#11-delivery-acknowledgement)
12. [Loading the app from the edge nginx](#12-loading-the-app-from-the-edge-nginx)
13. [Building the edge image (app included)](#13-building-the-edge-image-app-included)

---

## 1. Sign-in and token exchange

The browser never holds a token. oauth2-proxy keeps the session (Redis) behind an HttpOnly cookie. The gateway swaps the external token for a short-lived internal JWT, so the services only ever trust one issuer.

```mermaid
sequenceDiagram
    autonumber
    actor B as Browser
    participant N as nginx
    participant P as oauth2-proxy (customer)
    participant IdP as Google (dev-idp in local)
    participant G as api-gateway
    participant U as user-service
    participant S as order-service

    B->>N: GET /checkout (no session)
    N-->>B: index.html + app bundles (built into the nginx image, public)
    B->>N: GET /api/users/me
    N->>P: auth_request /_auth/customer
    P-->>N: 401
    N-->>B: 401 ProblemDetail {loginUrl: /oauth2/customer/start}
    B->>N: /oauth2/customer/start?rd=/checkout
    N->>P: start
    P-->>B: 302 to IdP (PKCE)
    B->>IdP: sign in
    IdP-->>B: 302 /oauth2/customer/callback?code=…
    B->>P: callback (via nginx)
    P->>IdP: redeem code → ID token
    P-->>B: Set-Cookie _smd_customer (HttpOnly) + 302 /checkout

    B->>N: GET /api/orders (cookie, X-Requested-With)
    N->>P: auth_request
    P-->>N: 202 + Authorization: Bearer <Google ID token>
    N->>G: GET /api/orders + Bearer <Google ID token>
    G->>G: TrustedIssuers: iss, aud, exp, email_verified → CUSTOMER
    alt not in the Caffeine cache (key = SHA-256 of the token)
        G->>U: POST /internal/auth/exchange (external token)
        U->>U: ExternalTokenValidator (same rules)<br/>UserProvisioning: find or create user (JIT registration)
        U-->>G: internal JWT (RS256, 5 min, iss=smd-internal, sub=user id, roles)
    end
    G->>S: GET /api/orders + Bearer <internal JWT>
    S->>S: resource server: smd-internal only · hasRole(CUSTOMER) · filter by sub
    S-->>B: 200 (via gateway and nginx)
```

Code:
- `api-gateway`: `security/TrustedIssuers`, `security/SecurityConfig`, `exchange/TokenExchangeFilter`
- `user-service`: `auth/TokenExchangeService`, `auth/UserProvisioning`, `auth/InternalTokenIssuer`
- nginx: `snippets/auth-customer.conf`

Admins follow the same path with the Okta instance (`/oauth2/admin/*`, cookie `_smd_admin`, access token, group `smd-admins`).

## 2. Guest cart → sign-in → merge

```mermaid
sequenceDiagram
    autonumber
    actor B as Browser (Angular)
    participant N as nginx
    participant G as api-gateway
    participant C as cart-service
    participant PS as product-service
    participant DB as DynamoDB (carts)

    Note over B: anonymous
    B->>N: POST /api/cart (X-Requested-With)
    N->>G: no session → forwarded without a token
    G->>C: POST /api/cart
    C->>DB: Put CART#35;<random UUID v4> (guest)
    C-->>B: 201 {cartId} + X-Cart-Id
    B->>B: localStorage smd.guestCartId (never a cookie)
    B->>C: POST /api/cart/items {productId, qty} + X-Cart-Id
    C->>PS: GET /api/products?ids=… (availability, OUT_OF_STOCK → 409)
    C->>DB: Update items (condition: version = n)
    C-->>B: cart view (current prices from product-service)

    Note over B: "Sign in to check out" → section 1 → back on /checkout
    B->>N: GET /api/users/me → 200 (signed in)
    B->>C: POST /api/cart/merge + X-Cart-Id (customer token via gateway)
    C->>DB: TransactWriteItems: Put customer cart (version condition)<br/>+ Delete guest cart (attribute_exists)
    C-->>B: customer cart (quantities summed, capped at 10)
    B->>B: forget smd.guestCartId
```

- **Merging is idempotent:** a second merge finds no guest cart and returns the customer's cart unchanged.
- **The customer's cart id** is derived from the user id, so two concurrent "create my cart" requests write the same item and only one wins.

Code:
- `cart-service`: `cart/CartService`, `persistence/CartRepository`
- `frontend`: `core/cart-store.ts`, `core/api.interceptor.ts`

## 3. Checkout: success

Synchronous and ACID: the caller gets `201 CONFIRMED` (or an error) right away.

```mermaid
sequenceDiagram
    autonumber
    actor B as Browser
    participant O as order-service<br/>OrderController
    participant UC as PlaceOrderUseCase
    participant CA as CartAdapter (Feign)
    participant I as OrderInitiationService
    participant PC as ParallelCalls (compose-*)
    participant US as user-service
    participant PS as product-service
    participant CS as CheckoutService
    participant DB as order_db

    B->>O: POST /api/orders/checkout (customer JWT)
    O->>CA: GET cart-service /api/cart (relayed JWT)
    CA-->>O: cartId, version, createdAt, lines
    Note over O: Idempotency-Key = cart:<id>:<createdAt>:v<version><br/>(unless the client sent one)
    O->>UC: placeOrder(sub, key, cartId, lines)
    UC->>DB: find order by idempotency key
    alt already exists
        UC-->>B: the same order, unchanged
    end
    UC->>I: initiate()  [own transaction]
    I->>DB: INSERT orders (INITIATED) + items<br/>INSERT outbox ORDER_INITIATED
    Note over I,DB: commits now, so tracking sees the order even if checkout fails

    par user-service
        PC->>US: GET /api/users/{id} (ACTIVE? default address?)
    and product-service
        PC->>PS: GET /api/products?ids=… (prices, active)
    end
    Note over PC: each call: Retry → CircuitBreaker → Bulkhead<br/>overall deadline 5 s · no fallback here

    UC->>CS: checkout(orderId, pricedLines, total, address)
    activate CS
    Note over CS,DB: ONE @Transactional (READ_COMMITTED, rollbackFor = Exception)
    loop each item, sorted by product_id (consistent lock order)
        CS->>DB: UPDATE inventory SET qty = qty - :n WHERE id = :id AND qty >= :n
        CS->>DB: INSERT stock_movements
    end
    CS->>DB: UPDATE customer_wallets SET balance = balance - :total WHERE balance >= :total
    CS->>DB: INSERT wallet_transactions (PAYMENT), payments (CAPTURED)
    CS->>DB: UPDATE orders SET CONFIRMED, total, shipping_address
    CS->>DB: INSERT outbox INVENTORY_RESERVED, PAYMENT_CAPTURED,<br/>ORDER_CONFIRMED (+cartId), INVENTORY_CHANGED × products
    deactivate CS
    CS-->>B: 201 CONFIRMED
```

**No remote calls inside the transaction.** The cart isn't touched either: cart-service empties it later, when it sees `ORDER_CONFIRMED` (section 5).

Tests: `CheckoutTest` (happy path, rollback), `CheckoutConcurrencyTest` (10 threads, last unit), `CheckoutFromCartTest`.

## 4. Checkout: rejection and rollback

```mermaid
sequenceDiagram
    autonumber
    participant UC as PlaceOrderUseCase
    participant CS as CheckoutService
    participant R as OrderRejectionService
    participant DB as order_db

    UC->>CS: checkout(...)
    activate CS
    CS->>DB: UPDATE inventory (item 1) → 1 row ✔
    CS->>DB: UPDATE inventory (item 2) → 0 rows ✘
    CS--xUC: OutOfStockException
    deactivate CS
    Note over CS,DB: ROLLBACK: item 1's stock is back, no payment, no events

    UC->>R: reject(orderId, OUT_OF_STOCK)  [separate bean, new transaction]
    R->>DB: UPDATE orders SET REJECTED, rejection_reason
    R->>DB: INSERT outbox ORDER_REJECTED
    UC-->>UC: CheckoutRejectedException → 409 ProblemDetail {reason, orderId}
```

| Failure | Order status | HTTP |
|---|---|---|
| `OUT_OF_STOCK` | REJECTED | 409 |
| `INSUFFICIENT_FUNDS` | REJECTED | 402 |
| `USER_INACTIVE`, `PRODUCT_NOT_FOUND`, `NO_SHIPPING_ADDRESS`, `EMPTY_CART` | REJECTED | 422 |
| `DEPENDENCY_UNAVAILABLE` (a lookup failed or timed out) | FAILED | 503 |

The rejection runs in a **separate bean** on purpose. Calling a `@Transactional` method of the same class bypasses the proxy, so the annotation would silently do nothing.

## 5. After checkout: events through delivery

```mermaid
sequenceDiagram
    autonumber
    participant OR as order-service<br/>OutboxRelay
    participant K as Kafka
    participant F as fulfillment-service
    participant SH as shipping-service
    participant T as order-tracking-service
    participant C as cart-service
    participant OS as order-service<br/>DeliveryEventsListener

    OR->>K: order-events: ORDER_INITIATED … ORDER_CONFIRMED (key = orderId)
    par every order event
        K->>T: record the event (section 7)
    and ORDER_CONFIRMED
        K->>F: OrderEventsListener → fulfillments RECEIVED + outbox FULFILLMENT_RECEIVED
    and ORDER_CONFIRMED with cartId
        K->>C: CheckoutClearing → remove the ordered quantities (EVENT#35; marker = idempotent)
    end
    F->>K: fulfillment-events: FULFILLMENT_RECEIVED
    K->>OS: order → IN_FULFILLMENT
    loop FulfillmentSimulator (@Scheduled, step-delay 2 s in local)
        F->>K: FULFILLMENT_PICKING, then FULFILLMENT_PACKED
    end
    K->>SH: FULFILLMENT_PACKED → shipment LABEL_CREATED + SHIPMENT_CREATED
    loop ShipmentSimulator
        SH->>K: SHIPMENT_PICKED_UP → IN_TRANSIT → OUT_FOR_DELIVERY → DELIVERED
    end
    K->>OS: PICKED_UP → SHIPPED · DELIVERED → DELIVERED + outbox ORDER_DELIVERED
    OS->>K: order-events: ORDER_DELIVERED
    Note over T: every fulfillment and shipping event is recorded too: the full timeline
```

- **Every consumer is idempotent:** a `processed_event` row in the same transaction, or DynamoDB conditions in tracking and cart.
- **Status only moves forward** (`OrderStatus.canAdvanceTo`).
- **Poison records** go to `<topic>.DLT` after 3 attempts.

## 6. Transactional outbox and trace continuation

```mermaid
sequenceDiagram
    autonumber
    participant BIZ as Business code<br/>(@Transactional)
    participant W as OutboxWriter
    participant OT as OutboxTracing
    participant DB as outbox_event
    participant R as OutboxRelay (@Scheduled 500 ms)
    participant K as Kafka
    participant L as Consumer listener

    BIZ->>W: orderEvent(type, orderId, …)
    W->>OT: traceParentFor(orderId)
    OT-->>W: current span's traceparent<br/>(or the order's latest one, for simulator work)
    W->>DB: INSERT (payload, trace_parent, created_at = clock_timestamp())
    Note over BIZ,DB: commits together with the business change (no dual write)

    R->>DB: SELECT … WHERE published_at IS NULL ORDER BY created_at<br/>LIMIT 100 FOR UPDATE SKIP LOCKED
    loop each row, in order
        R->>OT: startPublishSpan(trace_parent) → span "outbox publish TYPE"
        R->>K: send(key, payload).get(timeout) (producer observation adds traceparent header)
        alt sent
            R->>DB: SET published_at = now()
        else failed
            R->>DB: attempts + 1, then stop the batch (keeps per-order ordering)
        end
    end
    K->>L: record + traceparent → listener observation continues the same trace
```

- **Delivery is at least once.** A crash between the Kafka ack and `published_at` re-sends the record, and consumers de-duplicate on `eventId`.
- **One order is one trace in Jaeger**, from the HTTP request to delivery ([observability.md](observability.md)).

## 7. Tracking write: idempotent and forward-only

The core of order-tracking-service (`persistence/TrackingRepository`): one `TransactWriteItems` per event.

```mermaid
sequenceDiagram
    autonumber
    participant K as Kafka
    participant L as OrderEventsListener<br/>→ TrackingService
    participant R as TrackingRepository
    participant D as DynamoDB order_tracking

    K->>L: event (eventId, type, orderId, userId, occurredAt)
    L->>R: write(event, StatusRanks.rankOf(eventType))
    R->>D: TransactWriteItems<br/>1. Put EVENT#35;<occurredAt>#35;<eventId> IF attribute_not_exists(PK)<br/>2. Update STATE IF attribute_not_exists(statusRank) OR statusRank < :rank
    alt both conditions pass
        D-->>R: OK → outcome "written"
    else condition 1 failed (same event again)
        D-->>R: TransactionCanceled [ConditionalCheckFailed, …]
        R-->>L: "duplicate": acknowledge, change nothing
    else only condition 2 failed (older status arrived late)
        R->>D: Put EVENT#35; alone (the timeline keeps it)
        R-->>L: "stale": acknowledge, status unchanged
    else throttling / network
        R--xL: throw → Kafka retries → DLT
    end
    L-->>K: acknowledge (only after the write or a confirmed duplicate)
```

- **Reads are always by key:**
  - `GET /api/tracking/orders/{id}` = one `Query` on `PK = ORDER#<id>`;
  - `/latest` = a strongly consistent `GetItem` on `STATE`, for read-your-writes right after checkout.
- **Admin listings come from order-service (Postgres)**, never a DynamoDB `Scan`.

## 8. Compensation saga: fulfillment fails

A **choreography** saga: no coordinator. order-service reacts to an event and undoes its own work in one local transaction. (An orchestrated saga would have a central component sending commands to each step instead.)

```mermaid
sequenceDiagram
    autonumber
    participant F as fulfillment-service
    participant K as Kafka
    participant OS as order-service<br/>DeliveryEventsListener
    participant CX as OrderCancellationService
    participant DB as order_db
    participant T as order-tracking-service
    participant P as product-service

    F->>K: FULFILLMENT_FAILED (after payment was captured)
    K->>OS: record
    OS->>CX: cancel(orderId)
    activate CX
    Note over CX,DB: one @Transactional
    CX->>DB: inventory + qty, stock_movements (ORDER_CANCELLED)
    CX->>DB: customer_wallets + total, wallet_transactions (REFUND)
    CX->>DB: payments → REFUNDED, orders → CANCELLED
    CX->>DB: outbox INVENTORY_RESTORED, PAYMENT_REFUNDED, ORDER_CANCELLED, INVENTORY_CHANGED
    deactivate CX
    Note over CX: idempotent: a second FULFILLMENT_FAILED changes nothing
    DB-->>K: (relay) events
    K->>T: timeline ends … > FULFILLMENT_FAILED > INVENTORY_RESTORED > PAYMENT_REFUNDED > ORDER_CANCELLED
    K->>P: availability updated
```

Try it: restart fulfillment-service with `--demo.simulation.failure-rate=1.0` ([README demo 7](../README.md#7-fulfillment-fails--refund--restock)). Test: `CompensationTest`.

## 9. Order details aggregator (parallel fan-out)

```mermaid
sequenceDiagram
    autonumber
    actor B as Browser
    participant O as OrderDetailsService
    participant DB as order_db
    participant PC as ParallelCalls<br/>(bounded executor, context-propagating)
    participant U as user-service
    participant P as product-service
    participant S as shipping-service
    participant T as order-tracking-service

    B->>O: GET /api/orders/{id}/details
    O->>DB: load order (owner = JWT sub, else 404)
    par
        PC->>U: customer name, email
    and
        PC->>P: product names (one batch call)
    and
        PC->>S: shipment, tracking number, ETA
    and
        PC->>T: latest status + timeline
    end
    Note over PC: total ≈ the slowest call, not the sum<br/>a failed or timed-out section → fallback "unavailable"
    O-->>B: OrderDetailsResponse {…, degraded, unavailableSections: ["shipping"]}
```

- The JWT, trace and MDC reach the worker threads through `ContextPropagatingTaskDecorator`. Spring Security registers its own context accessor, so the security context travels too (checked by `ParallelCallsTest`).
- The Feign interceptor relays the caller's token on every parallel call.
- Metric: `composition.duration{degraded}`.

## 10. Admin restock → storefront availability

```mermaid
sequenceDiagram
    autonumber
    actor A as Admin (Okta)
    participant O as order-service<br/>InventoryAdminService
    participant DB as order_db
    participant K as Kafka inventory-events
    participant P as product-service<br/>InventoryEventsListener
    participant C as Caffeine caches
    actor S as Shopper

    A->>O: POST /api/admin/inventory/{id}/restock {quantity, note}
    O->>DB: one transaction: inventory + qty, stock_movements (RESTOCK, performed_by),<br/>outbox INVENTORY_CHANGED {productId, quantityOnHand}
    O-->>A: 200 {quantityOnHand}
    DB-->>K: relay (key = productId)
    K->>P: INVENTORY_CHANGED
    P->>P: level = OUT_OF_STOCK (0) · LOW_STOCK (≤ 5) · IN_STOCK<br/>upsert product_availability (skips duplicates and older events)
    P->>C: evict the affected catalog entries
    S->>P: GET /api/products/{slug} → availability IN_STOCK (never a count)
```

This is **eventually consistent** on purpose. Anonymous catalog traffic never reaches order-service. The authoritative stock check is the checkout transaction, so the worst case is "shown in stock, rejected at checkout with 409".

## 11. Delivery acknowledgement

```mermaid
sequenceDiagram
    autonumber
    actor B as Customer
    participant O as order-service<br/>DeliveryAcknowledgementService
    participant DB as order_db
    participant T as order-tracking-service

    B->>O: POST /api/orders/{id}/acknowledge-delivery
    alt not the owner
        O-->>B: 404
    else status != DELIVERED (and not already COMPLETED)
        O-->>B: 409
    else DELIVERED
        O->>DB: COMPLETED + delivery_acknowledged_at + outbox DELIVERY_ACKNOWLEDGED
        O-->>B: 200 COMPLETED
    else already COMPLETED
        O-->>B: 200, unchanged (idempotent)
    end
    DB-->>T: (relay, Kafka) DELIVERY_ACKNOWLEDGED, the highest status rank: the timeline ends here
```

## 12. Loading the app from the edge nginx

One nginx serves the Angular files from its own image and proxies the API. Each kind of file has its own caching rule (`nginx-proxy/nginx/templates/default.conf.template`).

```mermaid
sequenceDiagram
    autonumber
    actor B as Browser
    participant N as nginx :80
    participant FS as /usr/share/nginx/html<br/>(Angular build, in the image)
    participant P as oauth2-proxy (admin)
    participant G as api-gateway

    B->>N: GET /orders/123 (an app route, no such file)
    N->>FS: try_files $uri $uri/ /index.html
    FS-->>N: index.html
    N-->>B: 200 index.html · Cache-Control no-cache (+ ETag, CSP and other security headers)
    B->>N: GET /main-5IQOQLN5.js, /styles-….css
    N-->>B: 200 gzip · Cache-Control public, max-age=31536000, immutable
    Note over B,N: next visit: index.html is revalidated (304 if unchanged)<br/>the hashed bundles come from the browser cache
    B->>N: GET /products/daypack-25l.svg
    N-->>B: 200 · Cache-Control public, max-age=86400
    B->>N: GET /api/storefront/home (X-Requested-With)
    N->>G: proxy (public route, client Authorization removed)
    G-->>B: 200 JSON (via nginx)

    B->>N: GET /admin/orders
    N->>P: auth_request /_auth/admin
    alt no Okta session
        P-->>N: 401
        N-->>B: 302 /oauth2/admin/start?rd=/admin/orders
    else admin session (group smd-admins)
        P-->>N: 202
        N->>FS: /index.html
        N-->>B: 200 index.html · Cache-Control no-store
    end
    B->>N: GET /nope.js (missing)
    N-->>B: 404, with no cache header, so a fixed deploy is seen at once
```

## 13. Building the edge image (app included)

```mermaid
sequenceDiagram
    autonumber
    actor D as Developer
    participant C as docker compose
    participant BK as BuildKit
    participant S1 as stage 1<br/>node:24-alpine
    participant S2 as stage 2<br/>nginx:1.28-alpine
    participant N as nginx container

    D->>C: docker compose up -d --build nginx
    C->>BK: build nginx-proxy/Dockerfile<br/>additional_contexts: frontend = ../frontend
    BK->>S1: COPY --from=frontend package.json package-lock.json
    S1->>S1: npm ci (cached layer while the lock file is unchanged)
    BK->>S1: COPY --from=frontend angular.json, tsconfig*.json, src/, public/
    S1->>S1: ng build --configuration production → dist/storefront/browser
    BK->>S2: rm the default pages, COPY dist/storefront/browser → /usr/share/nginx/html
    S2-->>C: image smd-edge-nginx:local
    C->>N: recreate (config mounted from nginx-proxy/nginx/, templates filled with envsubst)
    N-->>D: http://localhost serves the new build

    opt UI development (no rebuilds)
        D->>D: cd frontend && npx ng build --watch
        D->>C: up -d with docker-compose.ui-watch.yml
        C->>N: mount frontend/dist/storefront/browser over /usr/share/nginx/html
        Note over D,N: every rebuild is live on the next page load, still through nginx (sign-in works)
    end
```

