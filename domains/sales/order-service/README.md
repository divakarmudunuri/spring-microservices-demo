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

## Implemented so far (phase 4)

- `POST /api/orders` with `{"items":[{"productId","quantity"}]}`, headers `Idempotency-Key` and, until phase 11, `X-Demo-User-Id`. `201` with the confirmed order, or a ProblemDetail carrying `orderId` and `reason`: `409` out of stock, `402` insufficient funds, `422` user inactive / product not found / no shipping address, `503` dependency unavailable.
- `GET /api/orders/{id}` (own orders only; someone else's is `404`).
- Where to look:
  - `checkout/PlaceOrderUseCase`: the steps, in order
  - `checkout/CheckoutService`: the one `@Transactional` (stock → wallet → payment → CONFIRMED → outbox)
  - `checkout/OrderInitiationService`, `checkout/OrderRejectionService`: the transactions before and after it
  - `composition/ParallelCalls`, `composition/CompositionExecutorConfig`: the parallel lookups
  - `client/user`, `client/product`: Feign clients (package-private, with their DTOs) and the adapters
  - `outbox/OutboxWriter`: events written in the same transaction (published from phase 6)

Try the parallel lookups with the chaos toggles (user-service +800 ms, product-service +600 ms): the checkout takes about 0.85 s, not 1.4 s, and the `local` log shows each call's time and the total.

**Status:** in progress. See `../../../CLAUDE.md` for the full spec and the implementation phases. Data model: `../../../data-model/`.
