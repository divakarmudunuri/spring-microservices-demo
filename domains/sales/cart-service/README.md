# cart-service

Guest and customer carts. A guest cart is identified by the `X-Cart-Id` header and merged into the customer's cart after login. The cart is cleared through Kafka after checkout.

| | |
|---|---|
| Port | 8087 |
| Storage | DynamoDB table `carts` (TTL for abandoned carts) |

## Endpoints

- `GET/POST/DELETE /api/cart`
- `POST /api/cart/items`, `PUT/DELETE /api/cart/items/{productId}`
- `POST /api/cart/merge`

## Notes

- Consumes `order-events` (`ORDER_CONFIRMED` with `cartId`)

## Implemented (phase 12)

| Endpoint | Who | Notes |
|---|---|---|
| `POST /api/cart` | guest | new cart: `201`, id in the body and in `X-Cart-Id` (customers: their own cart, `200`) |
| `GET /api/cart` | guest (`X-Cart-Id`) or customer (JWT) | lines with the **current** name, picture, price, level and line totals (one batch call to product-service), subtotal; `degraded: true` without prices if product-service is down |
| `POST /api/cart/items` `{productId, quantity}` | guest or customer | adds to the line; `409` out of stock, `404` unknown product, `422` beyond 10 of a product or 50 lines |
| `PUT /api/cart/items/{productId}` `{quantity}` | guest or customer | `0` removes the line |
| `DELETE /api/cart/items/{productId}`, `DELETE /api/cart` | guest or customer | |
| `POST /api/cart/merge` + `X-Cart-Id` | customer | guest lines added (summed, capped), guest cart deleted, atomically; safe to repeat |

- Admins: `403` on every cart endpoint. A guest can never reach a customer's cart, even with its id.
- **`X-Cart-Id`, not a cookie:** the guest cart id is a bearer secret; as a cookie the browser would send it on every (cross-site) request and to every path. Never logged in full.
- **Storage** (`persistence/CartRepository`): DynamoDB `carts`, every write conditional on `version` (one retry, then `409`), TTL on `expiresAt` (guests 7 days, customers 30, pushed forward on every write). No prices are stored.
- **Cleared after checkout** (`cart/CheckoutClearing`): consumes `order-events`; `ORDER_CONFIRMED` with a `cartId` removes the ordered quantities (later additions stay; an empty cart is deleted), atomically with an `EVENT#<eventId>` marker. Not cleared by order-service directly: no remote calls in the checkout transaction, and a failed checkout leaves the cart untouched.
- product-service through Feign with Retry → CircuitBreaker → Bulkhead (`client/product/`).

**Status:** implemented. See `../../../CLAUDE.md` for the full spec and the implementation phases. Data model: `../../../data-model/`.
