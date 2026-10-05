# fulfillment_db and shipping_db

The two event-driven services after checkout. Neither makes synchronous calls; everything they need arrives in events.

![fulfillment_db and shipping_db](diagrams/04-fulfillment_shipping_db.svg)

```mermaid
erDiagram
    fulfillments ||--|{ fulfillment_items : contains
    fulfillments {
        UUID id PK
        UUID order_id UK
        UUID user_id
        VARCHAR status "RECEIVED / PICKING / PACKED / FAILED"
        VARCHAR warehouse_code
        VARCHAR failure_reason
        TIMESTAMPTZ next_step_at "simulator"
        BIGINT version
    }
    fulfillment_items {
        BIGINT id PK
        UUID fulfillment_id FK
        UUID product_id
        INT quantity
    }
    shipments {
        UUID id PK
        UUID order_id UK
        UUID user_id "ownership"
        VARCHAR tracking_number UK
        VARCHAR carrier
        VARCHAR status "LABEL_CREATED..DELIVERED"
        JSONB shipping_address
        DATE estimated_delivery
        TIMESTAMPTZ delivered_at
        TIMESTAMPTZ next_step_at "simulator"
        BIGINT version
    }
```

Schemas: [`sql/04-fulfillment_db.sql`](sql/04-fulfillment_db.sql), [`sql/05-shipping_db.sql`](sql/05-shipping_db.sql). Both databases also contain `outbox_event` and `processed_event` tables with the same shape as in `order_db` (not drawn).

## Tables

| Table | Purpose | Key rules |
|---|---|---|
| `fulfillments` | One per confirmed order | Unique `order_id` (a redelivered `ORDER_CONFIRMED` can't create a second one); `failure_reason` set exactly when `FAILED`; `next_step_at` tells the simulator when to advance it |
| `fulfillment_items` | What to pick | Unique `(fulfillment_id, product_id)` |
| `shipments` | One per packed order | Unique `order_id` and `tracking_number`; `user_id` copied from the event so customers can only see their own; `delivered_at` set exactly when `DELIVERED` |

## Status flows

```mermaid
stateDiagram-v2
    direction LR
    state fulfillment {
        [*] --> RECEIVED
        RECEIVED --> PICKING
        PICKING --> PACKED
        RECEIVED --> FAILED
        PICKING --> FAILED
    }
    state shipment {
        [*] --> LABEL_CREATED
        LABEL_CREATED --> PICKED_UP
        PICKED_UP --> IN_TRANSIT
        IN_TRANSIT --> OUT_FOR_DELIVERY
        OUT_FOR_DELIVERY --> DELIVERED
    }
```

