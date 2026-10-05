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

**Status:** not implemented yet. See `../../../CLAUDE.md` for the full spec and the implementation phases. Data model: `../../../data-model/`.
