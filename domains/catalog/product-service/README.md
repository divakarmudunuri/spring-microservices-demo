# product-service

Public product catalog: categories, products, and stock *levels* (a read model fed by `inventory-events`). Cached with Caffeine and HTTP caching headers.

| | |
|---|---|
| Port | 8083 |
| Storage | PostgreSQL `product_db` |

## Endpoints

- `GET /api/categories`
- `GET /api/products`, `GET /api/products/{idOrSlug}`, `GET /api/products?ids=`

## Notes

- Consumes `inventory-events`
- Never exposes exact stock counts

## Implemented so far (phase 3)

- `GET /api/categories`
- `GET /api/products?page=&size=` (active products, by name; `size` ≤ 100)
- `GET /api/products/{idOrSlug}`
- `GET /api/products?ids=a,b,c` (batch for Feign callers, ≤ 100 ids; unknown or inactive ids are left out)
- Every product carries `availability` (`IN_STOCK` / `LOW_STOCK` / `OUT_OF_STOCK`), never a stock count.
- Chaos toggles (`local` only), same as user-service: `POST /internal/chaos` with `{"latencyMs": …, "failureRate": …}`.

Filters, search, sorting, caching and the `inventory-events` consumer come in phase 12.

**Status:** in progress. See `../../../CLAUDE.md` for the full spec and the implementation phases. Data model: `../../../data-model/`.
