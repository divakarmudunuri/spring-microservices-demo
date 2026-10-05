# How to test Kafka

How to see, check and break the event flow by hand:
- the topics and the records on them;
- consumer groups and lag;
- the outbox behind the producers;
- what happens with a duplicate or a poison message.

The automated tests are listed [at the end](#automated-tests). Every command below was run against the local stack (`docker/docker-compose.yml` + the services in the `local` profile).

Related: [events.md](events.md) (the contract) · [design patterns 9, 11, 12, 22](design-patterns.md#c-data-consistency) · [sequence diagrams §5–6](sequence-diagrams.md#5-after-checkout-events-through-delivery).

## Setup

The Kafka CLI tools ship inside the broker container, so you don't need to install anything. Run all of this from the repository root, in one shell:

```bash
kafka() { docker exec -i smd-kafka-1 /opt/kafka/bin/kafka-$1.sh --bootstrap-server localhost:9092 "${@:2}"; }
API=http://localhost:8080
CUSTOMER=$(dev-idp/dev-token.sh customer)
```

**Kafka UI** at http://localhost:8090 shows the same information point-and-click: topics, messages with key, headers and value, consumer groups and lag, and the DLTs.

## 1. Topics

```bash
kafka topics --list
```

```
fulfillment-events
fulfillment-events.DLT
inventory-events
inventory-events.DLT
order-events
order-events.DLT
shipping-events
shipping-events.DLT
```

```bash
kafka topics --describe --topic order-events          # 3 partitions, replication 1 (local)
```

The producing services create the topics on startup (`NewTopic` beans), and the error handlers create the `.DLT` topics on first use.

## 2. Follow one order's events

Place an order, then read its records. The key is the order id, so all of them sit on **one partition, in order**.

```bash
ORDER=$(curl -s -X POST $API/api/orders -H "Authorization: Bearer $CUSTOMER" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" -d '{"items":[{"productId":"20000000-0000-4000-8000-000000000002","quantity":1}]}' | jq -r .id)

kafka console-consumer --topic order-events --from-beginning --timeout-ms 8000 \
  --formatter-property print.key=true --formatter-property print.headers=true --formatter-property key.separator=' | ' \
  | grep "$ORDER" | cut -c1-160
```

```
eventType:ORDER_INITIATED,traceparent:00-a744157d96bb528a9026b0f190de4b3c-34ccbc43e0fda806-01 | 43596857-… | {"source": "order-service", …
eventType:INVENTORY_RESERVED,traceparent:00-a744157d96bb528a9026b0f190de4b3c-0c7afb381e1c2ce3-01 | 43596857-… | …
eventType:PAYMENT_CAPTURED,traceparent:00-a744157d96bb528a9026b0f190de4b3c-9c05b045e3e0afcd-01 | 43596857-… | …
eventType:ORDER_CONFIRMED,traceparent:00-a744157d96bb528a9026b0f190de4b3c-a5f9c40a278cb833-01 | 43596857-… | …
eventType:ORDER_DELIVERED,traceparent:00-a744157d96bb528a9026b0f190de4b3c-fd9540e855caacf2-01 | 43596857-… | …
```

What to notice:
- **`eventType` header:** set by the outbox relay. Consumers can route on it without parsing the value.
- **`traceparent` header:** the **same trace id** on every event of the order. That is the trace continued through the outbox ([tracing.md](tracing.md)).
- **The value** is the JSON envelope: `eventId`, `eventType`, `orderId`, `userId`, `occurredAt`, `source`, `version`, `payload`.

Do the same on `fulfillment-events` and `shipping-events` for the rest of the journey. Use `inventory-events` (keyed by product id) for stock changes.

## 3. Consumer groups and lag

```bash
kafka consumer-groups --list
kafka consumer-groups --describe --group order-tracking-service
```

```
GROUP                  TOPIC         PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG  CONSUMER-ID
order-tracking-service order-events  0          95              95              0    consumer-order-tracking-service-1-…
order-tracking-service order-events  1          82              82              0    …
order-tracking-service order-events  2          41              41              0    …
```

- **The groups:** one per consuming service: `fulfillment-service`, `shipping-service`, `order-service`, `order-tracking-service`, `cart-service`, `product-service`.
- **`LAG`** is how many records a group still has to process. It should be 0 shortly after activity.
- **Over time:** Prometheus has `sum by (application) (kafka_consumer_fetch_manager_records_lag)`, and Grafana shows it in *Consumer lag*.

## 4. The outbox behind the topic

Producers never call Kafka from business code. They write `outbox_event` rows in the same transaction, and a relay publishes them. To check that nothing is stuck:

```bash
docker exec smd-postgres-1 psql -U order_svc -d order_db -c \
  "SELECT count(*) FILTER (WHERE published_at IS NULL) AS pending, count(*) AS total, max(published_at) AS last_published FROM outbox_event;"

docker exec smd-postgres-1 psql -U order_svc -d order_db -c \
  "SELECT event_type, created_at::time(0), published_at IS NOT NULL AS published, left(trace_parent, 40) AS trace_parent
     FROM outbox_event WHERE aggregate_id = '$ORDER' ORDER BY created_at;"
```

```
 pending | total
---------+-------
       0 |   259

     event_type     | created_at | published |               trace_parent
--------------------+------------+-----------+------------------------------------------
 ORDER_INITIATED    | 15:58:43   | t         | 00-a744157d96bb528a9026b0f190de4b3c-d354
 …
```

fulfillment-service (`fulfillment_db`, user `fulfillment_svc`) and shipping-service (`shipping_db`, user `shipping_svc`) have the same table.

`outbox.pending` and `outbox.publish.failures` are also metrics (Grafana: *Outbox pending*). To see the outbox hold events while Kafka is away, stop the broker (`docker stop smd-kafka-1`) and place an order:
- checkout still succeeds;
- `pending` grows;
- after `docker start smd-kafka-1`, the relay publishes the backlog **in order**.

## 5. Duplicate delivery is harmless (idempotent consumers)

Delivery is at least once, so re-send an event exactly as it was and check that nothing changes:

```bash
RECORD=$(kafka console-consumer --topic order-events --from-beginning --timeout-ms 8000 \
  --formatter-property print.key=true --formatter-property key.separator='|' | grep "^$ORDER|" | grep '"ORDER_CONFIRMED"' | head -1)

echo "$RECORD" | kafka console-producer --topic order-events \
  --reader-property parse.key=true --reader-property key.separator='|'
```

**Expect:**
- **order-tracking-service** logs `DUPLICATE` (its conditional write fails on the existing event item), and the order's item count in DynamoDB doesn't change ([testing-dynamodb.md](testing-dynamodb.md#4-idempotency-and-forward-only-status)).
- **fulfillment-service** and **cart-service** find the `eventId` already processed and skip it: no second fulfillment, no second cart clearing.
- **The order's status** stays where it was (`curl -s $API/api/orders/$ORDER -H "Authorization: Bearer $CUSTOMER" | jq .status`). Status only moves forward.

## 6. A poison message goes to the dead letter topic

```bash
before=$(kafka get-offsets --topic order-events.DLT | awk -F: '{s+=$3} END {print s+0}')
echo "poison-demo|this is not JSON" | kafka console-producer --topic order-events \
  --reader-property parse.key=true --reader-property key.separator='|'
sleep 5
echo "DLT records: $before → $(kafka get-offsets --topic order-events.DLT | awk -F: '{s+=$3} END {print s+0}')"

kafka console-consumer --topic order-events.DLT --from-beginning --timeout-ms 6000 \
  --formatter-property print.headers=true --formatter-property print.key=true \
  | LC_ALL=C grep -a -o 'kafka_dlt-original-consumer-group:[a-z-]*'
```

```
DLT records: 0 → 3
kafka_dlt-original-consumer-group:cart-service
kafka_dlt-original-consumer-group:fulfillment-service
kafka_dlt-original-consumer-group:order-tracking-service
```

- **Three DLT records:** three groups consume `order-events`, and each one gives up on the record independently.
- **No retries for this one:** a deserialization error goes straight to the DLT. A record that deserializes but keeps failing is retried 3 times with backoff first.
- **The partition moves on,** so later events aren't blocked.
- **The DLT headers** say why it failed: `kafka_dlt-exception-fqcn`, `kafka_dlt-exception-message`, `kafka_dlt-original-topic`, `-partition`, `-offset`. They're binary-safe, hence `grep -a`. Kafka UI shows them readably.

The poison record also stays on `order-events`, at its offset. Consumer groups have moved past it; a new group reading from the beginning would hit it again and dead-letter it again.

## 7. Replay a topic into a fresh consumer group

A read model can be rebuilt by consuming the topic again from the start under a new group. To *look* without touching the services' groups:

```bash
kafka console-consumer --topic shipping-events --from-beginning --group replay-check-$(date +%s) --timeout-ms 5000 \
  --formatter-property print.key=true | wc -l
```

## Automated tests

All of them use Testcontainers (a real Kafka broker) and Awaitility, and need no running stack:

| Test | Proves |
|---|---|
| order-service `OutboxRelayTest` | outbox rows become records, in order, keyed by order id (product id on `inventory-events`) |
| order-service `OutboxRelayFailureTest` | a failed send stops the batch and counts an attempt |
| order-service `OutboxTracingTest` | the stored `traceparent` continues as the record's header |
| order-service `DeliveryEventsTest` | fulfillment/shipping events move the order forward, never back |
| order-service `CompensationTest` | `FULFILLMENT_FAILED` → refund + restock, idempotently |
| fulfillment-service `FulfillmentFlowTest` | `ORDER_CONFIRMED` → fulfillment; the same event twice is handled once; an unusable event → `order-events.DLT` |
| shipping-service `ShippingFlowTest` | `FULFILLMENT_PACKED` → shipment; duplicates; DLT |
| order-tracking-service `OrderEventsConsumerTest` | events from every topic become the timeline; duplicates; DLT |
| cart-service `CheckoutClearingTest` | `ORDER_CONFIRMED` empties the cart once |
| product-service `AvailabilityTest` | `INVENTORY_CHANGED` updates the level; duplicates and older events ignored |

```bash
./gradlew :order-service:test --tests '*Outbox*'
./gradlew :fulfillment-service:test :order-tracking-service:test
```
