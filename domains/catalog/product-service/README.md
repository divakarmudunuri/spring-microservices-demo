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

## Storefront catalog (phase 12)

- `GET /api/products?category=<slug>&q=<text>&featured=true&sort=name|price-asc|price-desc|newest&page=&size=`: every filter optional; `q` is a case-insensitive match on name or description (`catalog/ProductSpecifications`).
- **Caching** (`api/CatalogCache`): Spring Cache with Caffeine, caches `categories`, `productPages`, `productById`, `featured` (60 s, max 1000 entries). The ids batch is not cached (Feign callers want current prices).
- **HTTP caching** (`api/CatalogHttpCaching`): anonymous catalog GETs get `Cache-Control: public, max-age=60` and an ETag (`If-None-Match` → `304`). Requests with a token are never marked public.
- **Stock levels from Kafka** (`availability/`): consumes `inventory-events` (`INVENTORY_CHANGED {productId, quantityOnHand}`) into `product_availability`: `0` → `OUT_OF_STOCK`, `≤ storefront.low-stock-threshold` (5) → `LOW_STOCK`, else `IN_STOCK`. Idempotent (`processed_event`), events older than `last_event_at` are ignored, and the caches are evicted after each change. **Eventually consistent:** a level can lag a moment; checkout in order-service is the authoritative check (worst case: shown in stock, `409` at checkout). A new consumer group replays the topic and rebuilds every level.

**Status:** in progress. See `../../../CLAUDE.md` for the full spec and the implementation phases. Data model: `../../../data-model/`.
