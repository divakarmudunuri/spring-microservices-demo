# How to test DynamoDB

Two services use DynamoDB:
- **order-tracking-service**: the `order_tracking` read model, one timeline per order;
- **cart-service**: the `carts` table.

Locally they talk to **DynamoDB Local** (`amazon/dynamodb-local`, port 8000, in `docker/docker-compose.yml`). This guide shows how to look at the tables and check the behaviour the code relies on:
- key design and queries;
- idempotent, forward-only tracking writes;
- optimistic locking on carts;
- TTL.

Every command below was run against the local stack.

Related: [data-model/05-dynamodb.md](../data-model/05-dynamodb.md) (the reviewed design) · [design patterns 3, 14, 16](design-patterns.md#14-cqrs-read-model) · [sequence §7](sequence-diagrams.md#7-tracking-write-idempotent-and-forward-only).

## Setup

**DynamoDB admin UI** at http://localhost:8001 lets you browse both tables, open items, run queries and scans. It's the quickest way to look around.

**From the command line,** nothing needs installing. DynamoDB Local speaks the normal DynamoDB JSON API over plain HTTP and doesn't check signatures, so `curl` with a dummy `Authorization` header works:

```bash
ddb() { curl -s http://localhost:8000 -H 'Content-Type: application/x-amz-json-1.0' \
  -H "X-Amz-Target: DynamoDB_20120810.$1" \
  -H 'Authorization: AWS4-HMAC-SHA256 Credential=local/20260101/us-east-1/dynamodb/aws4_request, SignedHeaders=host, Signature=local' \
  -d "$2"; }
```

With the AWS CLI installed, the same operations look like `aws dynamodb query --endpoint-url http://localhost:8000 …`, with any dummy credentials and region.

## 1. Tables, keys, indexes, TTL

```bash
ddb ListTables '{}'
ddb DescribeTable '{"TableName":"order_tracking"}' | jq -c '{keys: .Table.KeySchema, items: .Table.ItemCount, status: .Table.TableStatus}'
ddb DescribeTable '{"TableName":"carts"}' | jq -c '{keys: .Table.KeySchema, gsi: [.Table.GlobalSecondaryIndexes[]? | {name: .IndexName, keys: .KeySchema}]}'
ddb DescribeTimeToLive '{"TableName":"carts"}' | jq -c .
```

```
{"TableNames":["carts","order_tracking"]}
{"keys":[{"AttributeName":"PK","KeyType":"HASH"},{"AttributeName":"SK","KeyType":"RANGE"}],"items":486,"status":"ACTIVE"}
{"keys":[{"AttributeName":"PK","KeyType":"HASH"}],"gsi":[{"name":"byOwner","keys":[{"AttributeName":"ownerUserId","KeyType":"HASH"}]}]}
{"TimeToLiveDescription":{"TimeToLiveStatus":"ENABLED","AttributeName":"expiresAt"}}
```

| Table | Partition key | Sort key | Items |
|---|---|---|---|
| `order_tracking` | `ORDER#<orderId>` | `STATE` | current status, `statusRank`, `userId`, `lastEventAt` |
| | `ORDER#<orderId>` | `EVENT#<occurredAt>#<eventId>` | one per event: the timeline |
| `carts` | `CART#<cartId>` | — | `ownerUserId` (absent for a guest), `items`, `version`, `expiresAt` (TTL) |
| | `EVENT#<eventId>` | — | "already applied" marker for checkout clearing (7-day TTL) |

The services create the tables on startup in `local` and `docker` (`DynamoDbTableInitializer`), from the same definitions as [`data-model/dynamodb/*.table.json`](../data-model/dynamodb/). In real AWS they would come from infrastructure-as-code.

## 2. Read one order's timeline (the read model)

Place an order (see [testing-kafka.md](testing-kafka.md#2-follow-one-orders-events)), or take an order id from the UI, then:

```bash
ORDER=43596857-ec20-4d3d-a0e0-6483bd5605e1
ddb Query "{\"TableName\":\"order_tracking\",\"KeyConditionExpression\":\"PK = :pk\",
            \"ExpressionAttributeValues\":{\":pk\":{\"S\":\"ORDER#$ORDER\"}}}" \
  | jq -r '.Items[] | [.SK.S, (.status.S // .currentStatus.S), (.statusRank.N // "")] | @tsv'
```

```
EVENT#2026-10-05T15:58:42.869Z#7eb3fd61-…	ORDER_INITIATED
EVENT#2026-10-05T15:58:42.920Z#280d3433-…	PAYMENT_CAPTURED
…
EVENT#2026-10-05T15:58:55.603Z#d730aa8e-…	SHIPMENT_DELIVERED
EVENT#2026-10-05T15:58:56.114Z#52bb58c4-…	ORDER_DELIVERED
STATE	ORDER_DELIVERED	85
```

- **One `Query` on the partition key** returns the state and the whole timeline, sorted by the sort key. `occurredAt` has a fixed width, so string order is time order.
- **This is exactly** what `GET /api/tracking/orders/{id}` does (`TrackingRepository.findTimeline`).

The "latest status" read used by the order-details aggregator is a **strongly consistent** `GetItem` on `STATE`, so a read right after checkout sees the latest write:

```bash
ddb GetItem "{\"TableName\":\"order_tracking\",\"Key\":{\"PK\":{\"S\":\"ORDER#$ORDER\"},\"SK\":{\"S\":\"STATE\"}},\"ConsistentRead\":true}" \
  | jq -c '.Item | map_values(.S // .N)'
```

```
{"currentStatus":"ORDER_DELIVERED","lastEventAt":"2026-10-05T15:58:56.114Z","SK":"STATE","statusRank":"85","PK":"ORDER#43596857-…","userId":"00000000-0000-4000-8000-0000000000c1","updatedAt":"…"}
```

The same data through the API, where ownership is checked (someone else's order is a 404):

```bash
curl -s http://localhost:8080/api/tracking/orders/$ORDER -H "Authorization: Bearer $(dev-idp/dev-token.sh customer)" | jq '.currentStatus, [.timeline[].status]'
```

## 3. Count items, never scan in code

```bash
ddb Query "{\"TableName\":\"order_tracking\",\"KeyConditionExpression\":\"PK = :pk\",
            \"ExpressionAttributeValues\":{\":pk\":{\"S\":\"ORDER#$ORDER\"}},\"Select\":\"COUNT\"}" | jq .Count     # 14
```

A `Scan` reads the whole table. It's fine for poking around locally, but the services never scan: admin order lists come from Postgres (order-service), and tracking is only ever read by key.

## 4. Idempotency and forward-only status

Every tracking write is **one `TransactWriteItems`** with two conditions (`TrackingRepository.write`):
1. put the `EVENT#…` item **if it doesn't exist**. A redelivered event has the same key, so it fails, and the write is a **duplicate**.
2. update `STATE` **if** `statusRank < :newRank`. An older event arriving late fails this, and then only the event item is written (**stale**).

**To see 1:** re-send an event to Kafka exactly as it was ([testing-kafka.md §5](testing-kafka.md#5-duplicate-delivery-is-harmless-idempotent-consumers)). Then:
- the count from §3 stays the same (14 → 14);
- order-tracking-service logs `DUPLICATE`;
- the timer `tracking.dynamodb.write{outcome="duplicate"}` goes up.

```bash
curl -s "http://localhost:9090/api/v1/query" --data-urlencode 'query=sum by (outcome) (tracking_dynamodb_write_seconds_count)' \
  | jq -c '[.data.result[] | {outcome: .metric.outcome, count: .value[1]}]'
```

**To see 2:** the automated test covers it (`TrackingRepositoryTest`: an older-status event after a newer one leaves `currentStatus` unchanged, and the timeline has both).

## 5. Carts: guest cart, version condition, TTL

Create a guest cart through the gateway, change it twice, and look at the item:

```bash
CART=$(curl -s -X POST http://localhost:8080/api/cart | jq -r .cartId)
curl -s -o /dev/null -X POST http://localhost:8080/api/cart/items -H "X-Cart-Id: $CART" -H 'Content-Type: application/json' \
  -d '{"productId":"20000000-0000-4000-8000-000000000001","quantity":2}'
curl -s -o /dev/null -X PUT http://localhost:8080/api/cart/items/20000000-0000-4000-8000-000000000001 -H "X-Cart-Id: $CART" \
  -H 'Content-Type: application/json' -d '{"quantity":3}'

ddb GetItem "{\"TableName\":\"carts\",\"Key\":{\"PK\":{\"S\":\"CART#$CART\"}}}" | jq -c '.Item | {PK: .PK.S,
  ownerUserId: (.ownerUserId.S // "(guest)"), version: .version.N,
  items: [.items.L[].M | {productId: .productId.S, quantity: .quantity.N}], expiresAt: .expiresAt.N}'
```

```
{"PK":"CART#88ce5e6f-…","ownerUserId":"(guest)","version":"3","items":[{"productId":"20000000-0000-4000-8000-000000000001","quantity":"3"}],"expiresAt":"1791820952"}
```

- **`version` is 3:** created, then two writes. Every write is conditional on the version it read.
- **`expiresAt`** is 7 days ahead for a guest cart (30 for a customer), and every write pushes it forward. `date -r <expiresAt>` (macOS) or `date -d @<expiresAt>` (Linux) shows the date. DynamoDB deletes expired items by itself; DynamoDB Local does too, but not promptly.

**Optimistic locking,** the condition cart-service relies on: a write based on an old version is rejected.

```bash
ddb PutItem "{\"TableName\":\"carts\",\"Item\":{\"PK\":{\"S\":\"CART#$CART\"},\"version\":{\"N\":\"2\"}},
              \"ConditionExpression\":\"version = :v\",\"ExpressionAttributeValues\":{\":v\":{\"N\":\"1\"}}}" | jq -c '{__type}'
```

```
{"__type":"com.amazonaws.dynamodb.v20120810#ConditionalCheckFailedException"}
```

In the service, that exception becomes "retry once, then `409 Cart changed concurrently`".

**A customer's cart** has no guest id. It's found through the **`byOwner` index**:

```bash
ddb Query '{"TableName":"carts","IndexName":"byOwner","KeyConditionExpression":"ownerUserId = :u",
            "ExpressionAttributeValues":{":u":{"S":"00000000-0000-4000-8000-0000000000c1"}}}' \
  | jq -c '[.Items[] | {PK: .PK.S, version: .version.N, lines: (.items.L | length)}]'
```

It comes back empty after a checkout: cart-service deletes the cart once `ORDER_CONFIRMED` has emptied it. Index reads are eventually consistent, so cart ids for customers are derived from the user id, to make concurrent "create my cart" requests collide on the same item.

Clean up the guest cart: `curl -s -X DELETE http://localhost:8080/api/cart -H "X-Cart-Id: $CART"` (204).

## 6. Health

The readiness check of each service includes its table (`DescribeTable`):

```bash
curl -s localhost:8086/actuator/health/readiness | jq -c '.components.trackingTable'   # order-tracking-service
curl -s localhost:8087/actuator/health/readiness | jq -c '.components.cartsTable'      # cart-service
```

```
{"status":"UP","details":{"table":"order_tracking","status":"ACTIVE"}}
```

To see it go DOWN, `docker stop smd-dynamodb-local-1`. Readiness turns `DOWN`, while liveness stays `UP`. Start it again with `docker start smd-dynamodb-local-1`; the data persists in a volume (`-sharedDb`).

## Automated tests

DynamoDB Local runs in Testcontainers (`GenericContainer`), so no running stack is needed:

| Test | Proves |
|---|---|
| order-tracking-service `TrackingRepositoryTest` | write → query in time order; the same event twice → one item; an older status after a newer one → both in the timeline, status unchanged |
| order-tracking-service `TableDefinitionTest` | the table the service creates matches `data-model/dynamodb/order_tracking.table.json` |
| order-tracking-service `TrackingIntegrationTest`, `OrderEventsConsumerTest` | events from Kafka → `GET /api/tracking/orders/{id}`; ownership (404) |
| order-tracking-service `HealthTest` | the table health indicator |
| cart-service `CartApiTest` | guest create/add/update/remove; a wrong `X-Cart-Id` can't read a guest cart; merge |
| cart-service `CartConcurrencyAndTtlTest` | concurrent updates hit the version condition; the TTL attribute is set |
| cart-service `TableDefinitionTest` | the `carts` table and its `byOwner` index match `data-model/dynamodb/carts.table.json` |

```bash
./gradlew :order-tracking-service:test :cart-service:test
```
