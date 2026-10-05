# Microservice design patterns in this project

A catalog of the microservice patterns used here. For each one:
- **what it is**;
- **where we use it and why**;
- **a code excerpt**, trimmed, with `// …` marking omitted lines;
- **a link to the code**, at the exact lines.

The groups follow the usual taxonomy (as in Chris Richardson's *microservices.io* pattern language). The patterns we deliberately did **not** use are at the end, with the reasons.

Related: [architecture](architecture.md) · [sequence diagrams](sequence-diagrams.md) · [README patterns tour](../README.md#patterns-tour).

| Group | Patterns |
|---|---|
| [A. Decomposition and data](#a-decomposition-and-data) | 1 Decompose by business capability · 2 Database per service · 3 Polyglot persistence |
| [B. Communication and integration](#b-communication-and-integration) | 4 API gateway · 5 Backend for frontend · 6 API composition · 7 Service registry + client-side discovery · 8 Declarative client + adapter (anti-corruption layer) · 9 Domain events (publish/subscribe) |
| [C. Data consistency](#c-data-consistency) | 10 Local ACID transaction · 11 Transactional outbox · 12 Idempotent consumer · 13 Saga (choreography) + compensating transaction · 14 CQRS read model · 15 Idempotency key · 16 Optimistic locking · 17 Atomic conditional update |
| [D. Resilience](#d-resilience) | 18 Timeout + retry with backoff · 19 Circuit breaker · 20 Bulkhead · 21 Fallback / graceful degradation · 22 Dead letter queue · 23 Rate limiting |
| [E. Security](#e-security) | 24 Access token + token exchange · 25 Defense in depth (ownership checks) |
| [F. Observability and operations](#f-observability-and-operations) | 26 Distributed tracing · 27 Log correlation · 28 Application metrics · 29 Health check API · 30 Externalized configuration · 31 Caching |

---

## A. Decomposition and data

### 1. Decompose by business capability

**What:** each service owns one business capability end to end. Services are grouped by domain, not by technical layer.

**Here:**
- `domains/` holds the business services: `identity`, `catalog`, `sales`, `delivery`.
- `platform/` holds how the system runs: discovery and the gateway.
- `experience/` shapes data for one UI: the BFF.
- Inventory and payment are *not* separate services: together with orders they form one bounded context, **checkout**, so that it can be one ACID transaction (pattern 10).

```groovy
// settings.gradle: flat project names, folders grouped by domain
include 'order-service'
project(':order-service').projectDir = file('domains/sales/order-service')
include 'fulfillment-service'
project(':fulfillment-service').projectDir = file('domains/delivery/fulfillment-service')
```

**Code:** [`settings.gradle`](../settings.gradle) · rationale in [architecture §2](architecture.md#2-services-and-infrastructure)

### 2. Database per service

**What:** each service has its own private database. Others reach its data only through its API or its events.

**Here:**
- Five Postgres databases, each owned by its own role, created by the init script.
- Two DynamoDB tables.
- No service connects to another service's database. Schemas are reviewed in [`data-model/`](../data-model/README.md).

```sql
-- data-model/sql/00-create-databases.sql (excerpt): one database + one owner role per service
CREATE ROLE order_svc       LOGIN PASSWORD 'order_local_pw';
CREATE ROLE product_svc     LOGIN PASSWORD 'product_local_pw';
-- …
CREATE DATABASE order_db   OWNER order_svc;
CREATE DATABASE product_db OWNER product_svc;
-- …
```

**Code:** [`data-model/sql/00-create-databases.sql`](../data-model/sql/00-create-databases.sql)

### 3. Polyglot persistence

**What:** each service picks the store that fits its access pattern.

**Here:**
- **Postgres** where relational constraints and ACID matter: checkout relies on `CHECK (quantity_on_hand >= 0)` and `CHECK (balance >= 0)`.
- **DynamoDB** where access is always by key, writes are append-heavy, and items expire (TTL): the order-tracking read model and carts.

```java
// order-tracking-service: one partition per order, the state item plus one item per event
public static String orderPk(UUID orderId) {
    return "ORDER#" + orderId;          // SK = STATE, or EVENT#<occurredAt>#<eventId>
}
```

**Code:** [`TrackingRepository`](../domains/delivery/order-tracking-service/src/main/java/com/smd/ordertrackingservice/persistence/TrackingRepository.java#L62) · [`CartRepository`](../domains/sales/cart-service/src/main/java/com/smd/cartservice/persistence/CartRepository.java) · [why DynamoDB](../README.md#why-dynamodb-here-and-not-in-order-service)

---

## B. Communication and integration

### 4. API gateway

**What:** a single entry point that routes requests to services and handles cross-cutting concerns:
- authentication;
- rate limiting;
- circuit breaking;
- correlation ids.

**Here:**
- Spring Cloud Gateway behind nginx. The routes are declared in YAML and resolved through Eureka (`lb://`).
- Each route has its own circuit breaker with a `503` ProblemDetail fallback, and its own timeouts.
- fulfillment-service is deliberately not routed.

```yaml
# api-gateway application.yml
routes:
  - id: user-service
    uri: lb://user-service
    predicates:
      - Path=/api/users/me/**,/api/admin/me
    filters:
      - name: CircuitBreaker
        args: { name: userService, fallbackUri: "forward:/fallback/user-service" }
    metadata: { connect-timeout: 1000, response-timeout: 3000 }
```

**Code:** [`api-gateway/application.yml`](../platform/api-gateway/src/main/resources/application.yml#L33-L50) · [`FallbackController`](../platform/api-gateway/src/main/java/com/smd/apigateway/fallback/FallbackController.java) · [`CorrelationIdFilter`](../platform/api-gateway/src/main/java/com/smd/apigateway/filter/CorrelationIdFilter.java)

### 5. Backend for frontend (BFF)

**What:** a service that shapes data for one particular client. It owns no data and holds no business rules.

**Here:** `storefront-bff` builds the home page in one call: categories, featured products and new arrivals, fetched in parallel from product-service. The gateway routes and secures; the BFF composes for one client.

```java
// storefront-bff StorefrontService
public StorefrontPages.Home home() {
    var categories = parallelCalls.submit("categories", products::categories);
    var featured = parallelCalls.submit("featured", () -> products.products(null, true, "name", HOME_LIST_SIZE));
    var newArrivals = parallelCalls.submit("newArrivals", () -> products.products(null, null, "newest", HOME_LIST_SIZE));

    List<String> unavailable = new ArrayList<>();
    return new StorefrontPages.Home(
            orEmpty("categories", categories, unavailable),
            orEmpty("featured", featured, unavailable),
            orEmpty("newArrivals", newArrivals, unavailable),
            !unavailable.isEmpty(), unavailable);
}
```

**Code:** [`StorefrontService.home`](../experience/storefront-bff/src/main/java/com/smd/storefrontbff/storefront/StorefrontService.java#L37-L51)

### 6. API composition (aggregator)

**What:** a query that needs data from several services calls them and joins the results in memory. Here the calls run **in parallel**, so the total time is about the slowest call, not the sum.

**Here:** `GET /api/orders/{id}/details` calls user-, product-, shipping- and order-tracking-service at the same time. A failed section is reported as unavailable, never as a 500.
- **Executor:** a bounded, named pool (`compose-*`) with `CallerRunsPolicy` for back-pressure.
- **Context:** `ContextPropagatingTaskDecorator` carries the trace, MDC and security context to the worker threads.
- **Deadline:** every future gets an overall `orTimeout`.

```java
// order-service OrderDetailsService: all four start now; none waits for another
var customer    = section("customer", () -> Optional.of(users.getCustomer(order.getUserId())));
var productInfo = section("products", () -> Optional.of(products.getProducts(itemIds)));
var shipment    = section("shipping", () -> shipping.findShipment(orderId));
var timeline    = section("tracking", () -> tracking.findTracking(orderId));
OrderDetails details = new OrderDetails(order, customer.join(), productInfo.join(), shipment.join(), timeline.join());

// any failure (after retries, open circuit, deadline) becomes "section unavailable"
private <T> CompletableFuture<Section<T>> section(String name, Supplier<Optional<T>> call) {
    return parallelCalls.submit(name, call)
            .handle((value, failure) -> failure != null ? Section.<T>unavailable(name, elapsedMs)
                                                         : Section.of(name, value, elapsedMs));
}

// ParallelCalls: never supplyAsync without our executor; always a deadline
public <T> CompletableFuture<T> submit(String name, Supplier<T> call) {
    return CompletableFuture.supplyAsync(() -> timed(name, call), executor)
            .orTimeout(properties.deadline().toMillis(), TimeUnit.MILLISECONDS);
}
```

**Code:**
- [`OrderDetailsService`](../domains/sales/order-service/src/main/java/com/smd/orderservice/details/OrderDetailsService.java#L64-L99)
- [`ParallelCalls`](../domains/sales/order-service/src/main/java/com/smd/orderservice/composition/ParallelCalls.java#L37-L42)
- [`CompositionExecutorConfig`](../domains/sales/order-service/src/main/java/com/smd/orderservice/composition/CompositionExecutorConfig.java#L21-L50)
- diagram: [sequence §9](sequence-diagrams.md#9-order-details-aggregator-parallel-fan-out)

### 7. Service registry + client-side discovery and load balancing

**What:** services register themselves in a registry. Callers look up instances by *name* and balance the load themselves, so no URLs or ports appear in code.

**Here:**
- Eureka (`discovery-server`) is the registry.
- Feign clients and the gateway (`lb://`) resolve services by name through Spring Cloud LoadBalancer.
- [README demo 9](../README.md#9-load-balancing-across-two-product-service-instances) shows round-robin across two product-service instances (10/10).

```java
// order-service: a service name, never a URL
@FeignClient(name = "user-service", configuration = UserClientConfig.class)
interface UserClient {

    @GetMapping("/api/users/{id}")
    UserDto getUser(@PathVariable("id") UUID id);
}
```

**Code:** [`UserClient`](../domains/sales/order-service/src/main/java/com/smd/orderservice/client/user/UserClient.java#L12-L17) · [`DiscoveryServerApplication`](../platform/discovery-server/src/main/java/com/smd/discoveryserver/DiscoveryServerApplication.java)

### 8. Declarative client + adapter (anti-corruption layer)

**What:** remote calls go through a declarative HTTP client (OpenFeign). An **adapter** translates the downstream model and errors into the caller's own domain terms, so the rest of the code never sees Feign, HTTP status codes or the other service's DTOs.

**Here:** every downstream service has its own package, `client/<service>/`, in the caller, holding:
- `XxxClient`;
- its DTOs;
- `XxxErrorDecoder`: `404` → a domain "not found", `5xx` → retryable, other `4xx` → not retried;
- `XxxAdapter`: resilience and mapping.

A `RequestInterceptor` relays the caller's JWT and correlation id.

```java
// UserErrorDecoder: HTTP status → domain/infrastructure exception
public Exception decode(String methodKey, Response response) {
    if (response.status() == 404) {
        return new CustomerNotFoundException("No user at " + response.request().url());
    }
    return DownstreamErrors.byStatus("user-service", response);   // 5xx/429 → retryable, other 4xx → not
}

// UserAdapter: the only thing the rest of order-service calls
public Customer getCustomer(UUID userId) {
    return toCustomer(client.getUser(userId));     // UserDto never leaves client/user/
}

// FeignHeadersInterceptor: relay, never forge, the caller's identity
Authentication caller = SecurityContextHolder.getContext().getAuthentication();
if (caller instanceof JwtAuthenticationToken jwt) {
    template.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.getToken().getTokenValue());
}
```

**Code:** [`UserErrorDecoder`](../domains/sales/order-service/src/main/java/com/smd/orderservice/client/user/UserErrorDecoder.java#L11-L16) · [`UserAdapter`](../domains/sales/order-service/src/main/java/com/smd/orderservice/client/user/UserAdapter.java#L33-L50) · [`FeignHeadersInterceptor`](../domains/sales/order-service/src/main/java/com/smd/orderservice/client/FeignHeadersInterceptor.java#L21-L34)

### 9. Domain events (publish/subscribe)

**What:** a service announces facts ("order confirmed") on a topic, and any interested service reacts. The producer doesn't know its consumers.

**Here:** Kafka topics `order-events`, `fulfillment-events`, `shipping-events` and `inventory-events`.
- Every event uses the same envelope (`eventId`, `eventType`, `orderId`, `userId`, `occurredAt`, `source`, `version`, `payload`).
- The record key is the order id, so all of an order's events stay in order on one partition.
- Each service keeps its own copy of the event classes; [`events.md`](events.md) is the contract.

```java
// order-service: the envelope every event travels in
public record EventEnvelope(UUID eventId, String eventType, UUID orderId, UUID userId,
                            Instant occurredAt, String source, int version, Object payload) {
}

// fulfillment-service reacts to ORDER_CONFIRMED (unknown types are skipped, not failed)
@KafkaListener(topics = "order-events")
public void onEvent(EventEnvelope event) {
    if ("ORDER_CONFIRMED".equals(event.eventType())) { /* … */ }
}
```

**Code:** [`EventEnvelope`](../domains/sales/order-service/src/main/java/com/smd/orderservice/outbox/EventEnvelope.java) · [`OrderEventsListener` (fulfillment)](../domains/delivery/fulfillment-service/src/main/java/com/smd/fulfillmentservice/fulfillment/OrderEventsListener.java) · [`events.md`](events.md)

---

## C. Data consistency

### 10. Local ACID transaction (instead of a distributed one)

**What:** keep the data that must change together in one database, and change it in **one local transaction**: no two-phase commit, no saga.

**Here:** checkout decrements stock, debits the wallet, records the payment, confirms the order and writes the events in one `@Transactional` method. Any failure rolls back all of it. The method follows these rules:
- **no remote calls inside:** lookups happen before it;
- **a consistent lock order:** items sorted by product id, so concurrent orders can't deadlock;
- **a separate bean:** so the Spring proxy applies;
- **`rollbackFor = Exception.class`:** so checked exceptions roll back too.

```java
// order-service CheckoutService
@Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
public Order checkout(UUID orderId, List<PricedLine> lines, BigDecimal total, ShippingAddress address) {
    // 1. stock: one atomic conditional UPDATE per item, in product-id order
    for (PricedLine line : sortedByProductId(lines)) {
        int left = inventory.decrement(line.productId(), line.quantity())
                .orElseThrow(() -> new OutOfStockException(line.productId()));
        stockMovements.save(StockMovement.sale(line.productId(), line.quantity(), orderId));
    }
    // 2.-3. payment: debit the wallet the same way, and record it in the ledger
    if (!wallets.debit(userId, total)) {
        throw new InsufficientFundsException(userId);
    }
    // … payment row, order CONFIRMED, outbox events: all committed together, or none
}
```

**Code:** [`CheckoutService.checkout`](../domains/sales/order-service/src/main/java/com/smd/orderservice/checkout/CheckoutService.java#L79-L121) · tests `CheckoutTest`, `CheckoutConcurrencyTest` · [README demo 2](../README.md#2-out-of-stock-rollback-proof)

### 11. Transactional outbox

**What:** to publish an event reliably together with a database change, write the event into an **outbox table in the same transaction**, and let a relay publish it afterwards. This avoids the *dual-write* problem: a commit with a lost send, or a send for a rolled-back change.

**Here:** order-, fulfillment- and shipping-service each have an `outbox_event` table, an `OutboxWriter` (inside the business transaction) and an `OutboxRelay`.
- The relay polls every 500 ms with `FOR UPDATE SKIP LOCKED`, so several instances can run safely.
- It publishes in `created_at` order and stops at the first failure, which keeps each order's events in sequence.
- Delivery is at least once.

```java
// OutboxWriter: joins the caller's transaction, it never starts its own
@Transactional(propagation = Propagation.MANDATORY)
public void orderEvent(String eventType, UUID orderId, UUID userId, Object payload) { /* INSERT INTO outbox_event … */ }

// OutboxRelay: one batch per transaction; the row locks are held until the rows are marked published
List<OutboxRow> rows = jdbc.sql("""
        SELECT id, topic, aggregate_id, event_type, payload::text AS payload, trace_parent
          FROM outbox_event
         WHERE published_at IS NULL
         ORDER BY created_at
         LIMIT :limit
           FOR UPDATE SKIP LOCKED""").param("limit", properties.batchSize()).query(OutboxRow.class).list();
for (OutboxRow row : rows) {
    try {
        send(row);                                   // kafka.send(record).get(timeout)
    } catch (Exception e) {
        publishFailures.increment();                 // attempts + 1, then stop: keeps per-order ordering
        break;
    }
    jdbc.sql("UPDATE outbox_event SET published_at = now() WHERE id = :id").param("id", row.id()).update();
}
```

**Code:** [`OutboxWriter`](../domains/sales/order-service/src/main/java/com/smd/orderservice/outbox/OutboxWriter.java#L48-L63) · [`OutboxRelay`](../domains/sales/order-service/src/main/java/com/smd/orderservice/outbox/OutboxRelay.java#L78-L129) · diagram: [sequence §6](sequence-diagrams.md#6-transactional-outbox-and-trace-continuation)

### 12. Idempotent consumer

**What:** because delivery is at least once, every consumer must make a redelivered message harmless.

**Here:**
- **Postgres consumers** insert the `eventId` into `processed_event` *in the same transaction* as their changes. A duplicate inserts nothing and is skipped.
- **The DynamoDB read model** uses conditional writes instead (pattern 14).
- **Status only moves forward,** so a late event can't move it back.

```java
// ProcessedEvents
@Transactional(propagation = Propagation.MANDATORY)
public boolean markProcessed(UUID eventId) {
    return jdbc.sql("INSERT INTO processed_event (event_id) VALUES (:id) ON CONFLICT DO NOTHING")
            .param("id", eventId).update() == 1;
}

// OrderProgressService: marker, status change and the next event commit together
@Transactional
public void apply(ConsumedEvent event, OrderStatus target) {
    if (!processedEvents.markProcessed(event.eventId())) {
        return;                                            // duplicate delivery
    }
    Order order = orders.findById(event.orderId()).orElseThrow();
    if (!order.advanceTo(target)) {
        return;                                            // status only moves forward
    }
    // …
}
```

**Code:** [`ProcessedEvents`](../domains/sales/order-service/src/main/java/com/smd/orderservice/delivery/ProcessedEvents.java#L19-L25) · [`OrderProgressService.apply`](../domains/sales/order-service/src/main/java/com/smd/orderservice/delivery/OrderProgressService.java#L43-L70) · tests `DeliveryEventsTest`, `FulfillmentFlowTest`

### 13. Saga (choreography) with a compensating transaction

**What:** a business process that spans services is a sequence of local transactions, each publishing an event. If a later step fails, *compensating* transactions undo the earlier ones.
- **Choreography:** services react to each other's events.
- **Orchestration:** a coordinator sends commands.

**Here:** after a successful checkout, the only cross-service failure left is "fulfillment fails after payment". order-service reacts to `FULFILLMENT_FAILED` by restocking, refunding and cancelling, in one local, idempotent transaction.

```java
// order-service OrderCancellationService
@Transactional(rollbackFor = Exception.class)
public boolean cancel(UUID orderId, String reason) {
    Order order = orders.findWithItemsById(orderId).orElseThrow();
    if (!order.cancel()) {
        return false;                                   // already cancelled: a second FULFILLMENT_FAILED changes nothing
    }
    for (OrderItem item : itemsSortedByProductId(order)) {   // same lock order as checkout
        int onHand = inventory.increment(item.getProductId(), item.getQuantity());
        stockMovements.save(StockMovement.cancellation(item.getProductId(), item.getQuantity(), orderId));
    }
    Payment payment = payments.findByOrderId(orderId).orElseThrow();
    payment.refund(clock.instant());
    wallets.credit(order.getUserId(), payment.getAmount());
    outbox.orderEvent(EventTypes.ORDER_CANCELLED, orderId, order.getUserId(), new OrderEventPayloads.OrderCancelled(reason));
    // … INVENTORY_RESTORED, PAYMENT_REFUNDED, INVENTORY_CHANGED
    return true;
}
```

**Code:** [`OrderCancellationService.cancel`](../domains/sales/order-service/src/main/java/com/smd/orderservice/compensation/OrderCancellationService.java#L76-L109) · test `CompensationTest` · [README demo 7](../README.md#7-fulfillment-fails--refund--restock) · diagram: [sequence §8](sequence-diagrams.md#8-compensation-saga-fulfillment-fails)

### 14. CQRS read model

**What:** a separate, query-optimized view, kept up to date from events and owned by a different service than the writes.

**Here, two of them:**
- **order-tracking-service** builds every order's timeline in DynamoDB from all order topics. It makes no synchronous calls and can be rebuilt by replaying the topics. One `TransactWriteItems` per event is both the **idempotency check** (put IF the event doesn't exist) and the **forward-only status** (update IF the rank is higher).
- **product-service** keeps `product_availability` (IN_STOCK / LOW_STOCK / OUT_OF_STOCK) from `inventory-events`, so anonymous catalog traffic never reaches order-service.

```java
// order-tracking-service TrackingRepository
Put putEvent = Put.builder().tableName(tableName).item(EVENT_SCHEMA.itemToMap(event, true))
        .conditionExpression("attribute_not_exists(PK)")                       // same event again → fails
        .build();
Update moveStatusForward = Update.builder().tableName(tableName).key(stateKey)
        .updateExpression("SET currentStatus = :status, statusRank = :rank, lastEventAt = :at, …")
        .conditionExpression("attribute_not_exists(statusRank) OR statusRank < :rank")   // older → fails
        .expressionAttributeValues(values).build();
try {
    dynamo.transactWriteItems(TransactWriteItemsRequest.builder()
            .transactItems(TransactWriteItem.builder().put(putEvent).build(),
                           TransactWriteItem.builder().update(moveStatusForward).build()).build());
    return WriteOutcome.WRITTEN;
} catch (TransactionCanceledException e) {
    if (failedCondition(e.cancellationReasons(), 0)) return WriteOutcome.DUPLICATE;
    if (failedCondition(e.cancellationReasons(), 1)) return putEventOnly(putEvent);   // STALE: timeline only
    throw e;                                                                          // let Kafka retry → DLT
}
```

**Code:** [`TrackingRepository.write`](../domains/delivery/order-tracking-service/src/main/java/com/smd/ordertrackingservice/persistence/TrackingRepository.java#L99-L136) · [`AvailabilityService.apply`](../domains/catalog/product-service/src/main/java/com/smd/productservice/availability/AvailabilityService.java#L42-L75) · diagram: [sequence §7](sequence-diagrams.md#7-tracking-write-idempotent-and-forward-only)

### 15. Idempotency key (idempotent API)

**What:** the client sends a unique key with a non-idempotent request. The server stores it with the result and returns the same result if the key comes again, so a retry or a double click can't create two orders.

**Here:**
- `POST /api/orders` requires `Idempotency-Key`; a unique constraint settles concurrent duplicates.
- `POST /api/orders/checkout` derives the key from the cart: `cart:<id>:<createdAt>:v<version>`.
- Wallet top-ups use the same idea.

```java
// order-service PlaceOrderUseCase
Optional<Order> existing = orders.findByIdempotencyKey(idempotencyKey);
if (existing.isPresent()) {
    return replay(existing.get(), userId);                 // the same order, unchanged
}
try {
    order = initiation.initiate(userId, idempotencyKey, cartId, lines);
} catch (DataIntegrityViolationException e) {
    // a concurrent request with the same key won the race on the unique constraint
    return replay(orders.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> e), userId);
}
```

**Code:** [`PlaceOrderUseCase`](../domains/sales/order-service/src/main/java/com/smd/orderservice/checkout/PlaceOrderUseCase.java#L101-L114) · [`CheckoutFromCartUseCase`](../domains/sales/order-service/src/main/java/com/smd/orderservice/checkout/CheckoutFromCartUseCase.java) · tests `CheckoutTest`, `CheckoutFromCartTest`, `WalletTest`

### 16. Optimistic locking

**What:** no lock while reading. The write succeeds only if the data hasn't changed since it was read (a version check). Otherwise, retry or report a conflict.

**Here:** every cart write in DynamoDB is conditional on `version`. cart-service retries once on a conflict, then returns `409`.

```java
// cart-service CartRepository
public void save(CartItem cart) {
    long expected = cart.getVersion();
    cart.setVersion(expected + 1);
    try {
        carts.putItem(PutItemEnhancedRequest.builder(CartItem.class).item(cart)
                .conditionExpression(versionIs(expected)).build());     // "version = :expected"
    } catch (ConditionalCheckFailedException e) {
        cart.setVersion(expected);
        throw new CartConflictException("Cart changed concurrently");
    }
}
```

**Code:** [`CartRepository.save`](../domains/sales/cart-service/src/main/java/com/smd/cartservice/persistence/CartRepository.java#L72-L83) · test `CartConcurrencyAndTtlTest`

### 17. Atomic conditional update

**What:** check and change in **one statement**, so two concurrent requests can't both pass the check. The database's row lock serializes them, and the condition is re-evaluated against the committed value.

**Here:** stock and wallet balance. Ten threads buying the last unit give exactly one success, and stock never goes negative. The `CHECK` constraints are a second line of defense.

```java
// InventoryRepository
public Optional<Integer> decrement(UUID productId, int quantity) {
    return jdbc.sql("""
            UPDATE inventory
               SET quantity_on_hand = quantity_on_hand - :qty, version = version + 1
             WHERE product_id = :productId AND quantity_on_hand >= :qty
            RETURNING quantity_on_hand""")
            .param("qty", quantity).param("productId", productId)
            .query(Integer.class).optional();          // empty = not enough stock
}
```

**Code:** [`InventoryRepository.decrement`](../domains/sales/order-service/src/main/java/com/smd/orderservice/inventory/InventoryRepository.java#L26-L36) · [`WalletRepository.debit`](../domains/sales/order-service/src/main/java/com/smd/orderservice/wallet/WalletRepository.java#L23-L31) · test `CheckoutConcurrencyTest` · [README demo 4](../README.md#4-concurrent-orders-for-the-last-units)

---

## D. Resilience

### 18. Timeout + retry with exponential backoff

**What:**
- **Timeout:** never wait forever on a remote call.
- **Retry:** retry only *transient* failures, only *idempotent* calls, with growing randomized delays so retries don't synchronize.

**Here:**
- every Feign client has its own connect and read timeouts;
- every fan-out future has a deadline;
- Resilience4j retries 3 times with exponential backoff and jitter, only on `5xx`/`429`/IO errors, never on a `4xx`;
- Feign's own `Retryer` stays off.

```yaml
# order-service application.yml
resilience4j:
  retry:
    retry-aspect-order: 1        # Retry( CircuitBreaker( Bulkhead( call )))
    configs:
      default:
        max-attempts: 3
        wait-duration: 100ms
        enable-exponential-backoff: true
        exponential-backoff-multiplier: 2
        enable-randomized-wait: true
        randomized-wait-factor: 0.5
        retry-exceptions:
          - com.smd.orderservice.client.DownstreamServerException   # 5xx, 429
          - feign.RetryableException                                # I/O errors, timeouts
```

**Code:** [`order-service/application.yml` (retry)](../domains/sales/order-service/src/main/resources/application.yml#L101-L133) · [Feign timeouts and pool](../domains/sales/order-service/src/main/resources/application.yml#L57-L99)

### 19. Circuit breaker

**What:** after too many failures, stop calling a failing service for a while (OPEN) and fail fast. Then let a few trial calls through (HALF_OPEN) before closing again.

**Here:** one breaker per downstream service, on the adapter methods:
- count-based window of 20 calls, opening at 50% failures;
- open for 10 s;
- `404`s and other `4xx`s don't count as failures;
- an open breaker never marks the service DOWN.

[README demo 6](../README.md#6-circuit-breaker-opening): with the breaker open, calls fail in 16 ms with a degraded answer.

```java
// UserAdapter: annotations on the adapter, not on the Feign interface
@Retry(name = RESILIENCE, fallbackMethod = "translateFailure")
@CircuitBreaker(name = RESILIENCE)
@Bulkhead(name = RESILIENCE)
public Customer getCustomer(UUID userId) {
    return toCustomer(client.getUser(userId));
}
```

**Code:** [`UserAdapter`](../domains/sales/order-service/src/main/java/com/smd/orderservice/client/user/UserAdapter.java#L33-L43) · [`application.yml` (circuitbreaker)](../domains/sales/order-service/src/main/resources/application.yml#L134-L167) · tests `UserAdapterTest`, `FeignClientTest`

### 20. Bulkhead

**What:** isolate resources per dependency, like a ship's watertight compartments, so one slow service can't exhaust the threads every other call needs.

**Here:** a semaphore bulkhead per downstream service (at most 10 concurrent calls) inside a bounded executor (16 core threads). The gateway also wraps each route in a bulkhead.

```yaml
resilience4j:
  bulkhead:
    configs:
      default:
        max-concurrent-calls: 10   # one slow dependency can't take every compose- thread
```

**Code:** [`application.yml` (bulkhead)](../domains/sales/order-service/src/main/resources/application.yml#L168-L175) · [`CompositionExecutorConfig`](../domains/sales/order-service/src/main/java/com/smd/orderservice/composition/CompositionExecutorConfig.java#L31-L50)

### 21. Fallback / graceful degradation

**What:** when a dependency fails, return the best partial answer instead of an error.

**Here:**
- The order-details aggregator, the BFF home page and the cart view return what they have, with `degraded: true` and `unavailableSections`.
- The gateway falls back to a `503` ProblemDetail.
- **Checkout deliberately has no fallback:** it must fail (`503`) rather than guess.

```java
// the aggregator: a failed section is reported, not thrown (see pattern 6)
.handle((value, failure) -> failure != null ? Section.<T>unavailable(name, elapsedMs)
                                             : Section.of(name, value, elapsedMs));

// checkout lookups: translate the failure, but never invent a value
private Customer translateFailure(UUID userId, Throwable failure) {
    throw DownstreamErrors.translate("user-service", failure, CustomerNotFoundException.class);
}
```

**Code:** [`OrderDetailsService.section`](../domains/sales/order-service/src/main/java/com/smd/orderservice/details/OrderDetailsService.java#L87-L99) · [`UserAdapter.translateFailure`](../domains/sales/order-service/src/main/java/com/smd/orderservice/client/user/UserAdapter.java#L40-L43) · [`FallbackController`](../platform/api-gateway/src/main/java/com/smd/apigateway/fallback/FallbackController.java)

### 22. Dead letter queue

**What:** a message that keeps failing is moved aside, to a dead letter topic, after a few attempts, so it doesn't block its partition forever.

**Here:**
- every consumer retries 3 times with exponential backoff, then publishes the record to `<topic>.DLT` on the same partition;
- undeserializable records and invalid events skip the retries;
- the DLTs can be inspected in Kafka UI.

```java
// every Kafka consumer's KafkaConsumerConfig
@Bean
DefaultErrorHandler kafkaErrorHandler(ProducerFactory<?, ?> bootProducerFactory) {
    ExponentialBackOff backOff = new ExponentialBackOff(500, 2.0);
    backOff.setMaxAttempts(2);   // retries after the first attempt → 3 attempts
    var recoverer = new DeadLetterPublishingRecoverer(deadLetterTemplate(bootProducerFactory),
            (record, failure) -> new TopicPartition(deadLetterTopic(record.topic()), record.partition()));
    DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
    handler.addNotRetryableExceptions(InvalidEventException.class);
    return handler;
}
```

**Code:** [`KafkaConsumerConfig`](../domains/sales/order-service/src/main/java/com/smd/orderservice/kafka/KafkaConsumerConfig.java#L34-L43) · test `FulfillmentFlowTest.anUnusableOrderConfirmedGoesToTheDeadLetterTopic` · [how to test it](testing-kafka.md)

### 23. Rate limiting

**What:** cap how many requests a client may make in a time window, to protect the services and share capacity fairly.

**Here, two layers:**
- **nginx** limits per IP (`limit_req` zones for the catalog, the API and logins).
- **The gateway** keeps a token bucket per caller: `user:<sub>` once signed in, the client IP otherwise, with lower limits for anonymous callers. Requests over the limit get `429` with `X-RateLimit-*` headers.

```java
// api-gateway TokenBucket: refilled lazily from the elapsed time
synchronized long tryConsume(long now) {
    tokens = Math.min(capacity, tokens + (now - lastRefill) * refillPerNano);
    lastRefill = now;
    if (tokens < 1) {
        return -1;                 // → 429
    }
    tokens -= 1;
    return (long) tokens;
}
```

**Code:** [`TokenBucket`](../platform/api-gateway/src/main/java/com/smd/apigateway/ratelimit/TokenBucket.java#L19-L27) · [`InMemoryRateLimiter`](../platform/api-gateway/src/main/java/com/smd/apigateway/ratelimit/InMemoryRateLimiter.java) · [`nginx.conf`](../nginx-proxy/nginx/nginx.conf) · test `RateLimitTest`

---

## E. Security

### 24. Access token + token exchange

**What:** services authorize with a token rather than a session. At the boundary, an **external** token (Google, Okta) is exchanged for an **internal** one that only the system trusts, so services never depend on outside identity providers.

**Here:**
- nginx and oauth2-proxy hold the session.
- The gateway validates the Google or Okta token: issuer, audience, `email_verified`, admin group.
- user-service validates it again and returns a 5-minute internal RS256 JWT, registering a new customer on their first sign-in.
- The gateway caches the internal token per external token (by its SHA-256) and swaps the `Authorization` header.

```java
// api-gateway TokenExchangeFilter: before routing, replace the external token
return exchange.getPrincipal()
        .ofType(JwtAuthenticationToken.class)
        .flatMap(auth -> client.internalToken(auth.getToken().getTokenValue(), auth.getToken().getExpiresAt()))
        .map(internal -> withAuthorization(exchange, internal))
        .defaultIfEmpty(withAuthorization(exchange, null))
        .flatMap(chain::filter);

// TokenExchangeClient: one exchange per few minutes per user, not per request
String key = sha256(externalToken);
CachedToken cached = cache.getIfPresent(key);
if (cached != null) {
    return Mono.just(cached.value());
}
return userService.post().uri("/internal/auth/exchange")
        .headers(h -> h.setBearerAuth(externalToken))
        // … 4xx → 401/403, 5xx or timeout → 503; cache until 30 s before the earlier expiry
```

**Code:**
- [`TokenExchangeFilter`](../platform/api-gateway/src/main/java/com/smd/apigateway/exchange/TokenExchangeFilter.java#L29-L37)
- [`TokenExchangeClient`](../platform/api-gateway/src/main/java/com/smd/apigateway/exchange/TokenExchangeClient.java#L47-L74)
- [`TrustedIssuers`](../platform/api-gateway/src/main/java/com/smd/apigateway/security/TrustedIssuers.java)
- [`UserProvisioning`](../domains/identity/user-service/src/main/java/com/smd/userservice/auth/UserProvisioning.java)
- [security.md](security.md) · diagram: [sequence §1](sequence-diagrams.md#1-sign-in-and-token-exchange)

### 25. Defense in depth (roles and ownership in every service)

**What:** don't rely on the edge alone. Every service checks the token and the caller's rights again.

**Here:**
- every service is an OAuth2 resource server trusting only the internal issuer;
- `@PreAuthorize` checks the role;
- queries filter by the JWT `sub`;
- someone else's order is a **404**, not a 403, so ids can't be probed.

```java
// order-service OrderQueryService
public Order getOwnOrder(UUID orderId, UUID userId) {
    return orders.findWithItemsById(orderId)
            .filter(order -> order.getUserId().equals(userId))
            .orElseThrow(() -> new OrderNotFoundException(orderId));     // → 404 for someone else's order
}
```

**Code:** [`OrderQueryService`](../domains/sales/order-service/src/main/java/com/smd/orderservice/order/OrderQueryService.java#L22-L26) · [`SecurityConfig` (order-service)](../domains/sales/order-service/src/main/java/com/smd/orderservice/security/SecurityConfig.java) · test `OrderSecurityTest`

---

## F. Observability and operations

### 26. Distributed tracing

**What:** one trace id follows a request across every service it touches, and each step records a span, so a slow or failing step can be found.

**Here:**
- Micrometer Tracing with the OpenTelemetry bridge, exporting over OTLP to Jaeger.
- HTTP and Feign calls carry the W3C `traceparent` automatically, and so do Kafka records (`observation-enabled`).
- **The gap the outbox creates is closed:** the writer stores `traceparent` with the row, and the relay re-opens a span with that parent.
- So **one order is one trace**: about 74 spans across 8 services, from checkout to delivery.

```java
// OutboxTracing: store the trace with the event…
public String traceParentFor(UUID aggregateId) {
    Span span = tracer.currentSpan();
    if (span != null) {
        TraceContext c = span.context();
        return "00-" + c.traceId() + "-" + c.spanId() + "-" + (Boolean.TRUE.equals(c.sampled()) ? "01" : "00");
    }
    // simulator work (no span): reuse the order's latest trace, so the order stays one trace
    return latestTraceParentOf(aggregateId);
}

// …and continue it when the relay publishes
public Span startPublishSpan(String traceParent, String eventType) {
    return propagator.extract(Map.of("traceparent", traceParent), Map::get)
            .name("outbox publish " + eventType).start();
}
```

**Code:** [`OutboxTracing`](../domains/sales/order-service/src/main/java/com/smd/orderservice/outbox/OutboxTracing.java#L41-L72) · test `OutboxTracingTest` · [how to look at traces](tracing.md)

### 27. Log correlation

**What:** every log line carries the ids needed to connect it with the rest of the request, so logs from many services can be searched together.

**Here:**
- every line shows `[application, traceId, spanId, correlationId, orderId]`;
- the correlation id comes from nginx or the gateway (`X-Correlation-Id`), is put into the MDC by each service, and is relayed by Feign;
- the order id comes from the Kafka record key (`OrderIdLogContext`);
- logs are JSON (ECS) outside `local`.

```java
// each service's CorrelationIdFilter
String correlationId = request.getHeader(HEADER);
if (!StringUtils.hasText(correlationId) || correlationId.length() > 100) {
    correlationId = UUID.randomUUID().toString();
}
MDC.put(MDC_KEY, correlationId);
response.setHeader(HEADER, correlationId);
try {
    chain.doFilter(request, response);
} finally {
    MDC.remove(MDC_KEY);
}
```

```yaml
logging.pattern.correlation: "[${spring.application.name:},%X{traceId:-},%X{spanId:-},%X{correlationId:-},%X{orderId:-}] "
logging.structured.format.console: ecs     # JSON everywhere except local
```

**Code:** [`CorrelationIdFilter` (product-service)](../domains/catalog/product-service/src/main/java/com/smd/productservice/observability/CorrelationIdFilter.java) · [`OrderIdLogContext`](../domains/sales/order-service/src/main/java/com/smd/orderservice/kafka/OrderIdLogContext.java) · [observability.md](observability.md)

### 28. Application metrics

**What:** services expose counters, timers and gauges, which Prometheus scrapes and Grafana charts.

**Here:**
- **Business metrics:** `checkout.attempts{outcome}`, `checkout.duration`, `composition.duration{degraded}`, `order.cancellations`, `outbox.pending`, `outbox.publish.failures`, `tracking.dynamodb.write{outcome}`.
- **Built-in metrics:** HTTP latency histograms, Resilience4j, Kafka consumer lag.

```java
// order-service PlaceOrderUseCase: every checkout counted and timed by outcome
Timer.Sample sample = Timer.start(meters);
String outcome = "error";
try {
    Order order = place(userId, idempotencyKey, cartId, lines);
    outcome = "confirmed";
    return order;
} catch (CheckoutRejectedException e) {
    outcome = e.reason().name().toLowerCase();          // out_of_stock, insufficient_funds, …
    throw e;
} finally {
    meters.counter("checkout.attempts", "outcome", outcome).increment();
    sample.stop(meters.timer("checkout.duration", "outcome", outcome));
}
```

**Code:** [`PlaceOrderUseCase.placeOrder`](../domains/sales/order-service/src/main/java/com/smd/orderservice/checkout/PlaceOrderUseCase.java) · [Grafana dashboard](../docker/grafana/provisioning/dashboards/smd-overview.json) · test `CheckoutMetricsTest`

### 29. Health check API

**What:** an endpoint that says whether the instance is alive (**liveness**) and whether it can serve traffic (**readiness**), for load balancers and orchestrators.

**Here:**
- every service exposes `/actuator/health/liveness` and `/readiness`;
- readiness includes the database or DynamoDB table and Kafka (with our own `KafkaHealthIndicator`, since Spring Boot has none);
- circuit breakers are visible in health but never mark a service DOWN.

```java
// KafkaHealthIndicator: readiness only; a broker outage takes the instance out of rotation, it isn't restarted
@Component("kafka")
class KafkaHealthIndicator extends AbstractHealthIndicator {
    @Override
    protected void doHealthCheck(Health.Builder builder) throws Exception {
        String clusterId = admin().describeCluster(new DescribeClusterOptions().timeoutMs(TIMEOUT_MS))
                .clusterId().get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        builder.up().withDetail("clusterId", clusterId);
    }
}
```

```yaml
management.endpoint.health.probes.enabled: true
management.endpoint.health.group.readiness.include: readinessState, db, kafka
```

**Code:** [`KafkaHealthIndicator`](../domains/sales/order-service/src/main/java/com/smd/orderservice/kafka/KafkaHealthIndicator.java#L32) · [`TrackingTableHealthIndicator`](../domains/delivery/order-tracking-service/src/main/java/com/smd/ordertrackingservice/persistence/TrackingTableHealthIndicator.java)

### 30. Externalized configuration

**What:** configuration lives outside the code: in profile-specific files and environment variables, bound to typed objects. The same build runs anywhere.

**Here:**
- `application.yml`, plus a `local` profile (seed data, chaos toggles, 100% sampling, readable logs) and a `docker` profile (container hostnames);
- secrets come from environment variables or `.env` files, never committed;
- custom settings are `@ConfigurationProperties` records.

```java
// order-service: the composition settings, bound from YAML (composition.*)
@ConfigurationProperties(prefix = "composition")
public record CompositionProperties(int corePoolSize, int maxPoolSize, int queueCapacity, Duration deadline) {
}
```

**Code:** [`CompositionProperties`](../domains/sales/order-service/src/main/java/com/smd/orderservice/composition/CompositionProperties.java) · [`application-local.yml`](../domains/sales/order-service/src/main/resources/application-local.yml) · [`nginx-proxy/.env.example`](../nginx-proxy/.env.example)

### 31. Caching

**What:** keep copies of frequently read, rarely changing data close to the reader.

**Here, three levels:**
- **In process:** product-service caches categories, product pages and products in Caffeine (60 s, bounded). The caches are evicted when an availability event arrives.
- **HTTP:** anonymous catalog responses get `Cache-Control: public, max-age=60` and an ETag. nginx caches hashed UI bundles for a year.
- **Gateway:** the token-exchange cache (pattern 24).

Responses that depend on the caller's identity are never cached.

```java
// product-service CatalogHttpCaching: only anonymous catalog GETs are cacheable
static boolean isAnonymousCatalogGet(HttpServletRequest request) {
    String path = request.getRequestURI();
    return "GET".equals(request.getMethod())
            && request.getHeader(HttpHeaders.AUTHORIZATION) == null
            && (path.startsWith("/api/products") || path.startsWith("/api/categories"));
}
// … a filter adds Cache-Control: public, max-age=60, and a ShallowEtagHeaderFilter adds the ETag
```

**Code:** [`CatalogHttpCaching`](../domains/catalog/product-service/src/main/java/com/smd/productservice/api/CatalogHttpCaching.java#L24-L62) · [`CachingConfig`](../domains/catalog/product-service/src/main/java/com/smd/productservice/CachingConfig.java) · test `CatalogHttpCachingTest`

---

## Patterns we deliberately did not use

| Pattern | Why not (here) | Where it would fit |
|---|---|---|
| **Shared database** | it couples services through their schemas and blocks independent changes | — (database per service, pattern 2) |
| **Two-phase commit / XA** | blocking, poorly supported by Kafka and cloud stores; we avoid needing it by putting checkout in one database | — |
| **Orchestrated saga** | one compensation path doesn't justify a coordinator; choreography stays simpler | longer processes with many steps (e.g. real card authorize → capture → ship) |
| **Event sourcing** | the order's state is a row; events are published *about* changes, not used as the source of truth. The tracking timeline is a read model, not an event store | auditing-heavy domains, temporal queries |
| **Change data capture (Debezium)** | the polling outbox relay is simpler to run and explain | high outbox volume, lower latency |
| **Kafka transactions / exactly-once** | idempotent consumers + at-least-once are simpler and enough here | stream processing pipelines |
| **Service mesh / sidecar** | Resilience4j, Micrometer and Spring Security do it in-process | many languages, mTLS everywhere, traffic shifting |
| **API versioning** | one client, deployed together; envelopes carry a `version` for events | public APIs, independently released clients |
| **Strangler fig** | there's no legacy system to replace | migrating a monolith |
