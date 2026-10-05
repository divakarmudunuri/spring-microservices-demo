# order-service

Checkout bounded context: orders, inventory, wallets, and payments in one database, so stock check + stock decrement + payment are one `@Transactional`. Also the order-details aggregator, transactional outbox, delivery acknowledgement, admin views, and the fulfillment-failure compensation.

| | |
|---|---|
| Port | 8081 |
| Storage | PostgreSQL `order_db` |

## Endpoints

- `POST /api/orders/checkout`, `POST /api/orders`
- `GET /api/orders`, `GET /api/orders/{id}`, `GET /api/orders/{id}/details`
- `POST /api/orders/{id}/acknowledge-delivery`
- `GET /api/wallet`, `POST /api/wallet/top-ups`
- `/api/admin/orders/**`, `/api/admin/payments/**`, `/api/admin/inventory/**`

## Notes

- Publishes `order-events` and `inventory-events` through the outbox
- Consumes `fulfillment-events` and `shipping-events`

## Implemented so far (phase 4)

- `POST /api/orders` with `{"items":[{"productId","quantity"}]}`, headers `Idempotency-Key` and, until phase 11, `X-Demo-User-Id`. `201` with the confirmed order, or a ProblemDetail carrying `orderId` and `reason`: `409` out of stock, `402` insufficient funds, `422` user inactive / product not found / no shipping address, `503` dependency unavailable.
- `GET /api/orders/{id}` (own orders only; someone else's is `404`).
- Where to look:
  - `checkout/PlaceOrderUseCase`: the steps, in order
  - `checkout/CheckoutService`: the one `@Transactional` (stock → wallet → payment → CONFIRMED → outbox)
  - `checkout/OrderInitiationService`, `checkout/OrderRejectionService`: the transactions before and after it
  - `composition/ParallelCalls`, `composition/CompositionExecutorConfig`: the parallel lookups
  - `client/user`, `client/product`: Feign clients (package-private, with their DTOs) and the adapters
  - `outbox/OutboxWriter`: events written in the same transaction (published from phase 6)

Try the parallel lookups with the chaos toggles (user-service +800 ms, product-service +600 ms): the checkout takes about 0.85 s, not 1.4 s, and the `local` log shows each call's time and the total.

## Resilience (phase 5)

Every Feign call goes through its adapter (`client/user/UserAdapter`, `client/product/ProductAdapter`), which applies, from the outside in: **Retry → CircuitBreaker → Bulkhead → call**. Config: `resilience4j.*` in `application.yml`; instances `userService`, `productService`.

| What | Setting |
|---|---|
| Timeouts | Feign connect 1 s / read 2 s per client, plus a 5 s overall deadline per parallel call (`composition.deadline`) |
| Retry | 3 attempts, exponential backoff with jitter (~100 ms, ~200 ms); only 5xx/429 (`DownstreamServerException`) and I/O errors/timeouts (`feign.RetryableException`), never a 4xx |
| Circuit breaker | last 20 calls, at least 10; opens at 50 % failures (or 80 % slower than 1.5 s); 10 s open, then 3 trial calls |
| Bulkhead | at most 10 concurrent calls per downstream service |
| Error decoders | `UserErrorDecoder`, `ProductErrorDecoder`: 404 → domain exception (user) / client error (product batch), 4xx → not retried, 5xx → retried |
| Fallback | none for checkout lookups (they must fail); `DownstreamErrors.translate` only turns infrastructure errors into `DependencyUnavailableException` (503) |

Watch it (`local` profile): `curl localhost:8081/actuator/circuitbreakers`, `/actuator/health` (shows each breaker; an open one never makes the service `DOWN`), `/actuator/retries`, `/actuator/bulkheads`. Make product-service fail with `curl -X POST localhost:8083/internal/chaos -H 'Content-Type: application/json' -d '{"failureRate":1.0}'`: the first orders take ~0.3 s (three attempts each), then the circuit opens and orders fail in ~20 ms without calling product-service.

## Outbox relay (phase 6)

`outbox/OutboxRelay` polls `outbox_event` every 500 ms (`SELECT … FOR UPDATE SKIP LOCKED LIMIT 100`, oldest first), sends each row with `KafkaTemplate.send(…).get(timeout)` (key = order id, or product id on `inventory-events`; header `eventType`), and marks it `published_at`. The first failure increments `attempts` and stops the batch, so events are never published out of order. Delivery is at least once. Producer: `acks=all`, idempotence on. Topics `order-events` and `inventory-events` are created on startup (`outbox/KafkaTopicsConfig`). Metrics: `outbox.pending`, `outbox.publish.failures`.

## Following delivery (phase 7)

`delivery/DeliveryEventsListener` consumes `fulfillment-events` and `shipping-events` (group `order-service`): `FULFILLMENT_RECEIVED` → `IN_FULFILLMENT`, `SHIPMENT_PICKED_UP` → `SHIPPED`, `SHIPMENT_DELIVERED` → `DELIVERED` + an `ORDER_DELIVERED` event. `delivery/OrderProgressService` applies them in one transaction with the `processed_event` marker; the status only moves forward (`OrderStatus.canAdvanceTo`), so a late event is ignored. `FULFILLMENT_FAILED` (refund + restock) comes in phase 8.

## Compensation: a choreography saga (phase 8)

Checkout needs no compensation: stock and payment are one local transaction. The only failure that can happen after payment is in another service: `FULFILLMENT_FAILED`. order-service reacts on its own (nobody coordinates; that's **choreography**, as opposed to an **orchestrator** telling each participant what to do) with `compensation/OrderCancellationService.cancel`, one `@Transactional`: stock back (+ `stock_movements` `ORDER_CANCELLED`, + `INVENTORY_CHANGED`), money back to the wallet (+ `wallet_transactions` `REFUND`), payment `REFUNDED`, order `CANCELLED`, and `INVENTORY_RESTORED` → `PAYMENT_REFUNDED` → `ORDER_CANCELLED`. It is idempotent: the event id goes into `processed_event` in the same transaction, a cancelled order is left alone, and the database allows one `REFUND` per order. A `FULFILLMENT_FAILED` for an order that already shipped goes to the DLT. Metric: `order.cancellations`.

See it: start fulfillment-service with `--demo.simulation.failure-rate=1.0` and place an order; ~4 s later stock and wallet are back and the tracking timeline ends in `ORDER_CANCELLED`.

## Order details: API composition (phase 9)

`GET /api/orders/{id}/details` (own orders; `X-Demo-User-Id` until phase 11) loads the order locally, then calls four services **at the same time** on the `compositionExecutor` (`details/OrderDetailsService`):

| Section | From | Feign client / adapter |
|---|---|---|
| `customer` (name, email) | user-service | `client/user` |
| item names and descriptions | product-service (one batch call) | `client/product` |
| `shipment` (tracking number, status, ETA) | shipping-service | `client/shipping` |
| `tracking` (current status + timeline) | order-tracking-service | `client/tracking` |

If a call fails or times out (after its retries, or past the 5 s deadline), its section is `null` and listed in `unavailableSections`, with `"degraded": true`; the response is still `200`. "Nothing yet" (no shipment, no tracking events: a 404 downstream) is just `null`, not degraded. Metric: `composition.duration` (tag `degraded`).

**Parallel vs. sequential, live:** with chaos latency on user-service (+800 ms) and product-service (+600 ms), the call takes ~0.85 s, not 1.4 s. The `local` log shows it:

```
order details 1ffc…: 888 ms in total, in parallel (customer 888 ms, products 696 ms, shipping 336 ms, tracking 88 ms); unavailable: [shipping]
```

```bash
curl -X POST localhost:8082/internal/chaos -H 'Content-Type: application/json' -d '{"latencyMs":800}'
curl -X POST localhost:8083/internal/chaos -H 'Content-Type: application/json' -d '{"latencyMs":600}'
curl -w '%{time_total}s\n' localhost:8081/api/orders/<orderId>/details -H 'X-Demo-User-Id: 00000000-0000-4000-8000-0000000000c1'
```

**Status:** in progress. See `../../../CLAUDE.md` for the full spec and the implementation phases. Data model: `../../../data-model/`.
