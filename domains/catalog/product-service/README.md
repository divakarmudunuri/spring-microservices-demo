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

**Status:** not implemented yet. See `../../../CLAUDE.md` for the full spec and the implementation phases. Data model: `../../../data-model/`.
