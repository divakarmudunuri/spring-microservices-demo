# api-gateway

Spring Cloud Gateway (WebFlux). The only backend entry point; it sits behind nginx. It validates the Google/Okta token that nginx forwards (in `local` also tokens from the dev identity provider, `../../dev-idp/`), exchanges it for an internal JWT from user-service, applies role-based route rules, and routes to services by name (`lb://`).

| | |
|---|---|
| Port | 8080 |
| Storage | None |

## Endpoints

- Routes `/api/**` to services (see CLAUDE.md 6.8)

## Notes

- Not published to the outside in the `docker` profile: only nginx can reach it
- Correlation-ID filter, per-route circuit breakers and timeouts, rate limiting
- Test without a browser: `TOKEN=$(../../dev-idp/dev-token.sh customer)`, then call `http://localhost:8080/api/...` with `Authorization: Bearer $TOKEN`

**Status:** not implemented yet. See `../../CLAUDE.md` for the full spec and the implementation phases. Data model: `../../data-model/`.
