# order-service

Checkout bounded context: orders, inventory, wallets, and payments in one database, so stock check + stock decrement + payment are one `@Transactional`. Also the order-details aggregator, transactional outbox, delivery acknowledgement, admin views, and the fulfillment-failure compensation.

| | |
|---|---|
| Port | 8081 |
| Storage | PostgreSQL `order_db` |

## Endpoints

- `POST /api/orders/checkout`, `POST /api/orders`
- `GET /api/orders`, `GET /api/orders/{id}`, `GET /api/orders/{id}/details`
- `POST /api/orders/{id}/acknowledge-delivery`
- `GET /api/wallet`, `POST /api/wallet/top-ups`
- `/api/admin/orders/**`, `/api/admin/payments/**`, `/api/admin/inventory/**`

## Notes

- Publishes `order-events` and `inventory-events` through the outbox
- Consumes `fulfillment-events` and `shipping-events`

**Status:** not implemented yet. See `../../../CLAUDE.md` for the full spec and the implementation phases. Data model: `../../../data-model/`.
