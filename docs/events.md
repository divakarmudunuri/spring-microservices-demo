# Events

The contract for every Kafka event in the system. Each service keeps its **own** copy of the classes it needs (no shared library), so this file is the source of truth. Change it first when an event changes, and bump the envelope `version` for a change that isn't backward compatible.

## Topics

| Topic | Key | Producer | Consumers |
|---|---|---|---|
| `order-events` | `orderId` | order-service | fulfillment-service (`ORDER_CONFIRMED` only), order-tracking-service (all), cart-service (`ORDER_CONFIRMED` with a `cartId`) |
| `inventory-events` | `productId` | order-service | product-service |
| `fulfillment-events` | `orderId` | fulfillment-service | shipping-service (`FULFILLMENT_PACKED`), order-service, order-tracking-service |
| `shipping-events` | `orderId` | shipping-service | order-service, order-tracking-service |
| `<topic>.DLT` | same as the source | error handlers | inspected manually |

3 partitions each, replication factor 1 locally, created by `NewTopic` beans in the producing service (automatic topic creation is off). Keying by `orderId` puts every event of one order in the same partition, in order.

## Envelope

Every event is a JSON object with the same envelope:

```json
{
  "eventId": "6f1c…",                 // UUID; consumers de-duplicate on it
  "eventType": "ORDER_CONFIRMED",
  "orderId": "b3b8…",                 // null on inventory-events
  "userId": "0000…c1",                // the customer who owns the order; null on inventory-events
  "occurredAt": "2026-10-04T23:51:05.658Z",   // UTC
  "source": "order-service",
  "version": 1,
  "payload": { … }                    // event-specific, below
}
```

Delivery is **at least once** (transactional outbox + relay), so consumers must be idempotent. Unknown event types are logged and skipped, never failed.

On the wire the value is this JSON as UTF-8 text (the producer sends it as a string; there are no Java type headers), plus a Kafka header `eventType`. Consumers map it to their own classes. Note that the producer stores the envelope as Postgres `jsonb`, so key order and spacing in the published JSON are not the same as above: always parse, never compare strings.

## `order-events` (order-service)

Written to `outbox_event` in the same transaction as the change they describe.

| Event | When | Payload |
|---|---|---|
| `ORDER_INITIATED` | order recorded, before any check | `items: [{productId, quantity}]`, `cartId` (nullable) |
| `INVENTORY_RESERVED` | checkout transaction | `items: [{productId, quantity}]` |
| `PAYMENT_CAPTURED` | checkout transaction | `paymentId`, `amount`, `currency` |
| `ORDER_CONFIRMED` | checkout transaction | `items: [{productId, quantity, unitPrice}]`, `totalAmount`, `currency`, `shippingAddress: {fullName, line1, line2, city, state, postalCode, country, phone}`, `cartId` (nullable) |
| `ORDER_REJECTED` | business rule failed (checkout rolled back) | `reason` (`OUT_OF_STOCK`, `INSUFFICIENT_FUNDS`, `USER_INACTIVE`, `PRODUCT_NOT_FOUND`, `EMPTY_CART`, `NO_SHIPPING_ADDRESS`), `detail` |
| `ORDER_FAILED` | a dependency was unavailable | `reason` (`DEPENDENCY_UNAVAILABLE`), `detail` |

Within one transaction the events are written in the order above (`INVENTORY_RESERVED` → `PAYMENT_CAPTURED` → `ORDER_CONFIRMED`), and the relay publishes them in that order.

`ORDER_CONFIRMED` carries everything downstream services need (items, prices, address), so fulfillment and shipping never call order-service back.

Still to come: `PAYMENT_REFUNDED`, `INVENTORY_RESTORED`, `ORDER_CANCELLED` (phase 8, compensation), `ORDER_DELIVERED` (phase 7), `DELIVERY_ACKNOWLEDGED` (phase 11).

## `inventory-events` (order-service)

| Event | When | Payload |
|---|---|---|
| `INVENTORY_CHANGED` | every stock change: checkout (one per product), and later cancellation and admin restock | `productId`, `quantityOnHand` |

`orderId` and `userId` are null. product-service turns the quantity into a public level (`IN_STOCK` / `LOW_STOCK` / `OUT_OF_STOCK`) and ignores events older than the last one it applied. The exact quantity never leaves the backend.

## `fulfillment-events`, `shipping-events`

Defined in phase 7.
