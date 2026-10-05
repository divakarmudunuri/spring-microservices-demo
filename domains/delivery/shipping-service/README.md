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

**Status:** not implemented yet. See `../../../CLAUDE.md` for the full spec and the implementation phases. Data model: `../../../data-model/`.
