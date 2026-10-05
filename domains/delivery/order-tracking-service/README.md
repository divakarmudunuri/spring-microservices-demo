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

**Status:** not implemented yet. See `../../../CLAUDE.md` for the full spec and the implementation phases. Data model: `../../../data-model/`.
