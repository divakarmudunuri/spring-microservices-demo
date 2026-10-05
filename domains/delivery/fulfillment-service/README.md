# fulfillment-service

Picks and packs confirmed orders. Fully event-driven; a simulator advances the status with configurable delays and failure rate.

| | |
|---|---|
| Port | 8084 |
| Storage | PostgreSQL `fulfillment_db` |

## Endpoints

- No public endpoints (internal only)

## Notes

- Consumes `order-events` (`ORDER_CONFIRMED`)
- Publishes `fulfillment-events` through the outbox

## Implemented (phase 7)

- Consumes `order-events` (group `fulfillment-service`): `ORDER_CONFIRMED` → a `RECEIVED` fulfillment (items + the shipping address, column added in `V2`) and `FULFILLMENT_RECEIVED`. Other order events are skipped. Idempotent through `processed_event` (same transaction) and the unique `order_id`.
- `fulfillment/FulfillmentSimulator`: every `demo.simulation.step-delay` (2 s in `local`) moves due fulfillments `RECEIVED → PICKING → PACKED`; with `demo.simulation.failure-rate` (0.0 by default) a fulfillment fails instead when leaving `RECEIVED` (`FULFILLMENT_FAILED`). `FULFILLMENT_PACKED` carries the address so shipping never calls back.
- Publishes `fulfillment-events` through its own outbox + relay (same pattern as order-service). Failed records → `order-events.DLT` after 3 attempts.
- Try a failure: `./gradlew :fulfillment-service:bootRun --args='--spring.profiles.active=local --demo.simulation.failure-rate=1.0'`.

**Status:** implemented. See `../../../CLAUDE.md` for the full spec and the implementation phases. Data model: `../../../data-model/`.
