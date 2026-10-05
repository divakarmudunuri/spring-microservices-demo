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

**Status:** not implemented yet. See `../../CLAUDE.md` for the full spec and the implementation phases. Data model: `../../data-model/`.
