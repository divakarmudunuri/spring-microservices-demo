# How to look at traces

Every request is traced, and **one order is one trace**: from the checkout HTTP call, through Kafka, to fulfillment, shipping, the tracking read model and cart clearing. This guide covers:
- how to **find** a trace;
- how to **read** it;
- what to do when a trace looks broken or is missing.

How tracing is wired is in [observability.md](observability.md), and the pattern is [design pattern 26](design-patterns.md#26-distributed-tracing).

| Where | URL |
|---|---|
| Jaeger UI | http://localhost:16686 |
| Jaeger API (v3) | http://localhost:16686/api/v3/… |
| Grafana → Explore → data source *Jaeger* | http://localhost:3000 |

In the `local` profile **every** request is traced (sampling 1.0). Other profiles sample 10% (`TRACING_SAMPLING_PROBABILITY`).

## 1. Find a trace

### In the Jaeger UI

1. Open http://localhost:16686 → **Search**.
2. **Service:** `order-service`. **Operation:** `http post /api/orders/checkout` (from the cart) or `http post /api/orders`.
3. **Lookback:** last 15 minutes → **Find Traces**. Each row is one checkout; the newest is at the top.

Other useful searches:

| To find | Service | Operation or tag |
|---|---|---|
| orders confirmed recently | `order-service` | Tags: `outbox.event_type=ORDER_CONFIRMED` |
| refunds (compensation saga) | `order-service` | Tags: `outbox.event_type=PAYMENT_REFUNDED` |
| slow requests | any | Min duration: `1s` |
| failed requests | any | Tags: `error=true` |
| everything the BFF did | `storefront-bff` | `http get /api/storefront/home` |

### From a log line

Every log line starts with `[application, traceId, spanId, correlationId, orderId]`:

```
[order-service,a744157d96bb528a9026b0f190de4b3c,34ccbc43e0fda806,phase15-demo-1,43596857-ec20-…] c.s.o.checkout.PlaceOrderUseCase : …
```

Paste the trace id into Jaeger's search box (top left, *Lookup by Trace ID*), or open `http://localhost:16686/trace/<traceId>` directly.

To find the trace of one particular request, send your own correlation id and search the service's log for it. The log is the terminal of `bootRun`, or `/tmp/<service>.log` if you started the services with the README script.

```bash
curl -s -X POST http://localhost:8080/api/orders/checkout -H "Authorization: Bearer $(dev-idp/dev-token.sh customer)" \
  -H 'X-Correlation-Id: my-test-1' | jq -r .id
grep my-test-1 /tmp/order-service.log | head -1 | grep -o '\[order-service,[0-9a-f]\{32\}' | cut -d, -f2
```

### From the data

The trace id is also stored next to the data it produced:
- **Outbox rows:** `outbox_event.trace_parent` (`00-<traceId>-<spanId>-01`):
  ```bash
  docker exec smd-postgres-1 psql -U order_svc -d order_db -c \
    "SELECT event_type, trace_parent FROM outbox_event ORDER BY created_at DESC LIMIT 3;"
  ```
- **Kafka records:** the `traceparent` header (see [testing-kafka.md §2](testing-kafka.md#2-follow-one-orders-events)).

### With the API

```bash
NOW=$(date -u +%Y-%m-%dT%H:%M:%SZ); AGO=$(date -u -v-30M +%Y-%m-%dT%H:%M:%SZ)   # Linux: date -u -d '30 min ago' +…

curl -s http://localhost:16686/api/v3/services | jq -c .services
curl -s -G http://localhost:16686/api/v3/traces \
  --data-urlencode query.service_name=order-service --data-urlencode "query.operation_name=http post /api/orders" \
  --data-urlencode query.start_time_min=$AGO --data-urlencode query.start_time_max=$NOW --data-urlencode query.search_depth=5 \
  | jq -r '[.result.resourceSpans[].scopeSpans[].spans[].traceId] | unique | .[]'
```

Fetch one trace and print it as a timeline:

```bash
TRACE=a744157d96bb528a9026b0f190de4b3c
curl -s http://localhost:16686/api/v3/traces/$TRACE | jq -r '
  [.result.resourceSpans[] | (.resource.attributes[] | select(.key == "service.name") | .value.stringValue) as $svc
   | .scopeSpans[].spans[] | {t: (.startTimeUnixNano | tonumber), svc: $svc, name}]
  | sort_by(.t) | (.[0].t) as $t0 | .[] | "\(((.t - $t0) / 1e9 * 100 | floor) / 100)s\t\(.svc)\t\(.name)"'
```

## 2. Read a checkout trace

A checkout from the cart produces **one trace of about 70 spans across 8 services, over about 14 s** (the simulators take 2 s per step in `local`). Shortened:

```
 0.00s  api-gateway              http post                         ← the request enters through nginx → gateway
 0.02s  order-service            http post /api/orders/checkout
 0.22s  order-service            HTTP GET                          ← Feign → cart-service: read the cart
 0.25s  cart-service             http get /api/cart
 0.41s  order-service            HTTP GET                          ┐ the parallel lookups: both start
 0.41s  order-service            HTTP GET                          ┘ at the same moment
 0.41s  user-service             http get /api/users/{id}
 0.42s  product-service          http get /api/products
 0.59s  order-service            outbox publish ORDER_INITIATED    ← the relay continues the request's trace
 0.59s  order-service            order-events send
 0.65s  order-service            outbox publish ORDER_CONFIRMED
 0.65s  cart-service             order-events receive              ← cart clearing
 0.67s  product-service          inventory-events receive          ← availability update
 0.68s  fulfillment-service      order-events receive
 0.68s  order-tracking-service   order-events receive              ← every event, written to DynamoDB
 1.01s  fulfillment-service      outbox publish FULFILLMENT_RECEIVED
 1.05s  order-service            fulfillment-events receive        ← order → IN_FULFILLMENT
 3.07s  fulfillment-service      outbox publish FULFILLMENT_PICKING   (simulator, 2 s later)
 5.09s  fulfillment-service      outbox publish FULFILLMENT_PACKED
 5.09s  shipping-service         fulfillment-events receive
 5.32s  shipping-service         outbox publish SHIPMENT_CREATED
 …
14.01s  shipping-service         outbox publish SHIPMENT_DELIVERED
14.39s  order-service            outbox publish ORDER_DELIVERED
14.40s  order-tracking-service   order-events receive
```

What to look at:
- **The parallel fan-out:** the user-service and product-service calls overlap. With [README demo 5](../README.md#5-slow-downstream--parallel-vs-sequential-timing) (1.5 s latency on both), the checkout span is about 1.5 s, not 3 s.
- **Retries:** a call that failed once and was retried shows two client spans for the same request.
- **The outbox hop:** `outbox publish …` starts a little after the checkout's transaction committed. That gap is the relay's poll interval (500 ms).
- **The Kafka hop:** `… send` (producer) → `… receive` (consumer) shows how long a record waited.
- **The async tail:** everything after `order-events receive` happens after the HTTP response was sent.
- **Span details:** click a span to see its tags: HTTP method and status, `outbox.event_type`, Kafka topic, partition and offset, exceptions.

The **[sequence diagrams](sequence-diagrams.md)** show the same flows as designed. Jaeger shows what actually happened.

### Compare two traces

Search for two checkouts, tick both and click **Compare**, for example one with `demo.chaos.latency-ms` set and one without. The extra time shows up on the slow service's spans.

## 3. In Grafana

**Explore** → data source **Jaeger**. Search by service and operation, or paste a trace id. Handy next to the *spring-microservices-demo* dashboard: when *Latency p95* jumps, look at the slow traces from the same minutes.

## 4. What is not traced (on purpose)

So that real requests aren't buried, these produce no spans:
- Prometheus scrapes and health checks (`/actuator/**`);
- Eureka registry traffic (`/eureka/**`);
- Spring Security's filter chain;
- `@Scheduled` polling (outbox relays, simulators).

This is set by each service's `observability/ObservabilityConfig` and `management.observations.enable.*`. A simulator step still belongs to its order's trace: the outbox writer reuses the order's latest `traceparent`.

## 5. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| a service is missing from Jaeger's service list | it hasn't handled a traced request since Jaeger started; Jaeger all-in-one keeps traces **in memory**, so restarting Jaeger empties it |
| no traces at all | the service isn't in the `local` profile (10% sampling) or can't reach `OTLP_TRACING_ENDPOINT` (default `http://localhost:4318/v1/traces`; `http://jaeger:4318/v1/traces` in `docker`) |
| the async part is a separate trace | the event was written before phase 15 (no `trace_parent` on the row), or by work with no span and no earlier event for the same order |
| the checkout trace ends at the HTTP response | normal for a few hundred ms: the async spans arrive as the relay and the simulators run. Refresh the trace |
| traces in tests | tests create spans but never export them (`management.otlp.tracing.export.enabled=false` in `java-conventions.gradle`) |
