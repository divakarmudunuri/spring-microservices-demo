# order-tracking-service

CQRS read model: records every event of every order as a timeline, from `ORDER_INITIATED` to `DELIVERY_ACKNOWLEDGED`.

| | |
|---|---|
| Port | 8086 |
| Storage | DynamoDB table `order_tracking` |

## Endpoints

- `GET /api/tracking/orders/{orderId}`, `GET /api/tracking/orders/{orderId}/latest`
- `GET /api/admin/tracking/orders/{orderId}`

## Notes

- Consumes `order-events`, `fulfillment-events`, `shipping-events`
- Idempotent conditional writes; status only moves forward

## Implemented so far (phases 6–7)

- Consumes `order-events`, `fulfillment-events` and `shipping-events` (consumer group `order-tracking-service`, from the earliest offset) into one timeline per order. Events from different topics can arrive in any order; the timeline is sorted by `occurredAt` and the status only moves forward.
- `GET /api/tracking/orders/{orderId}`: current status + timeline (one DynamoDB `Query`). `GET /api/tracking/orders/{orderId}/latest`: current status only (strongly consistent `GetItem`). Ownership checks arrive in phase 11.
- Where to look:
  - `persistence/TrackingRepository`: the two-action `TransactWriteItems` (idempotent event Put + forward-only STATE Update) and how cancellation reasons are read
  - `tracking/StatusRanks`: the rank of every event type
  - `events/KafkaConsumerConfig`: 3 attempts with exponential backoff, then `<topic>.DLT`; bad JSON and invalid envelopes go straight to the DLT
  - `persistence/DynamoDbTableInitializer`: creates the table from `classpath:dynamodb/order_tracking.table.json` (a copy of `data-model/`, checked by a test) when `tracking.dynamodb.create-table=true`
- Health: `trackingTable` in `/actuator/health` (`DescribeTable`). Metric: `tracking.dynamodb.write` timer, tag `outcome` = written / duplicate / stale / error.
- **Rebuild:** the table only holds what the events said, so it can be rebuilt by replaying the topics with a new consumer group from the earliest offset (e.g. `--spring.kafka.consumer.group-id=tracking-rebuild`). Replaying into an existing table is harmless: every event is a duplicate.
- No "list all orders" endpoint on purpose: that would be a full-table `Scan`. Admins list orders from order-service and open one timeline here.

**Status:** in progress. See `../../../CLAUDE.md` for the full spec and the implementation phases. Data model: `../../../data-model/`.
