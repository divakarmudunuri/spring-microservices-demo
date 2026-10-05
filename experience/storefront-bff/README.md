# storefront-bff

Backend-for-frontend for the storefront: composes home-page and product-page data with parallel `CompletableFuture` + Feign calls. No database.

| | |
|---|---|
| Port | 8088 |
| Storage | None |

## Endpoints

- `GET /api/storefront/home`
- `GET /api/storefront/products/{slug}`

## Notes

- Public (no login needed)

## Implemented (phase 12)

- `GET /api/storefront/home` → `{categories, featured, newArrivals, degraded, unavailableSections}`: three Feign calls to product-service **in parallel** (`storefront/StorefrontService`, `composition/ParallelCalls` on a bounded, context-propagating executor, the same pattern as order-service). A failed section is an empty list, named in `unavailableSections`; never a 500. Cached 30 s for anonymous callers (a degraded page is not cached).
- `GET /api/storefront/products/{slug}` → `{product, related, categories, ...}`: the product and the categories in parallel; the related products need the product's category, so they start once it arrives. `404` unknown product, `503` if the product itself can't be loaded.
- No database, no business rules. **BFF vs gateway:** the gateway routes and secures every request; the BFF composes data for one client's screens.
- Live, with product-service slowed by its chaos toggle (+500 ms per call): the home page takes ~0.54 s (`home page: 535 ms in total, in parallel`), not 1.5 s.

**Status:** implemented. See `../../CLAUDE.md` for the full spec and the implementation phases. Data model: `../../data-model/`.
