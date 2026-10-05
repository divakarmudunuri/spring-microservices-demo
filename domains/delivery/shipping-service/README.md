# shipping-service

Creates shipments for packed orders and simulates delivery.

| | |
|---|---|
| Port | 8085 |
| Storage | PostgreSQL `shipping_db` |

## Endpoints

- `GET /api/shipments/by-order/{orderId}`
- `GET /api/admin/shipments`, `GET /api/admin/shipments/{trackingNumber}`

## Notes

- Consumes `fulfillment-events` (`FULFILLMENT_PACKED`)
- Publishes `shipping-events` through the outbox

## Implemented so far (phase 7)

- Consumes `fulfillment-events` (group `shipping-service`): `FULFILLMENT_PACKED` → a `LABEL_CREATED` shipment (tracking number like `SMD7K2Q9XW4PJ3`, carrier `DEMO-EXPRESS`, address from the event, estimated delivery in 3 days) and `SHIPMENT_CREATED`. Idempotent through `processed_event` and the unique `order_id`.
- `shipment/ShipmentSimulator`: every `demo.simulation.step-delay` moves due shipments `PICKED_UP → IN_TRANSIT → OUT_FOR_DELIVERY → DELIVERED` (sets `delivered_at`), each with its event.
- `GET /api/shipments/by-order/{orderId}` (used by the aggregator in phase 9). Ownership checks and the admin listing arrive in phase 11.
- Publishes `shipping-events` through its own outbox + relay. Failed records → `fulfillment-events.DLT`.

## Security and admin (phase 11)

Internal JWTs only. `GET /api/shipments/by-order/{orderId}`: the owner or an ADMIN (someone else's: `404`). Admins: `GET /api/admin/shipments?status=&page=&size=` (newest first) and `GET /api/admin/shipments/{trackingNumber}`.

**Status:** in progress. See `../../../CLAUDE.md` for the full spec and the implementation phases. Data model: `../../../data-model/`.
