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

## Implemented so far (phase 10)

- **Routes** in `application.yml` (`spring.cloud.gateway.server.webflux.routes`), each `lb://<service>` with its own circuit breaker and `connect-timeout` / `response-timeout`. Only the paths in CLAUDE.md 6.8 are routed; everything else (e.g. `/api/users/{id}`, `/internal/**`, fulfillment-service) is a 404. `/api/shipments/**` is GET only. In `local`: `curl localhost:8080/actuator/gateway/routes`.
- **Correlation id** (`filter/CorrelationIdFilter`): keeps nginx's `X-Correlation-Id` or generates one, passes it downstream, returns it.
- **Request log** (`filter/RequestLoggingFilter`): `GET /api/categories → product-service 200 OK in 9 ms [correlationId=…]`, with the trace id in the log pattern.
- **Circuit breaker fallback** (`fallback/FallbackController`): connection refused, timeout, no instance in Eureka or an open circuit → `503` ProblemDetail (`/problems/service-unavailable`, with `service`). A downstream service's own error responses pass through unchanged.
- **Rate limiting** (`ratelimit/`): in memory (no Redis), a token bucket per caller: `user:<sub>` once authenticated (phase 11), otherwise `ip:<client>` from the last `X-Forwarded-For` entry (the one nginx adds). Anonymous: 30 burst / 10 per s; authenticated: 60 / 30. `429` with `X-RateLimit-*` headers. Per gateway instance.

## Security (phase 11)

- **Who is trusted** (`security/TrustedIssuers`): Google (`iss=https://accounts.google.com`, `aud` = our client id, `email_verified`) → `CUSTOMER`; Okta (`aud=api://default`, `groups` ∋ `smd-admins`, else no role → 403) → `ADMIN`; in `local` only, the dev-idp issuers (`security.dev-issuers`; startup fails if set elsewhere, `DevIssuersGuard`). Any other issuer, including the internal `smd-internal`, → 401.
- **Token exchange** (`exchange/TokenExchangeFilter`, `exchange/TokenExchangeClient`): the external token is swapped for an internal JWT from user-service (`POST lb://user-service/internal/auth/exchange`), cached by SHA-256 of the token until 30 s before the earlier expiry. Services never see Google/Okta tokens. user-service down → 503, refused → 403.
- **Route rules** (`security/SecurityConfig`): public GETs on products/categories/storefront, cart open (cart-service decides), `/api/admin/**` → ADMIN, orders/wallet/tracking/shipments and `/api/users/me` → CUSTOMER, anything else denied. A present-but-invalid token is 401 even on public routes.
- 401/403 are ProblemDetail; a 401 carries `loginUrl` (`/oauth2/customer/start`, or `/oauth2/admin/start` on admin paths). Stateless, no CSRF here (nginx enforces it), CORS only for `http://localhost:4200` in `local`.
- Try it (`local`, dev-idp running): `curl localhost:8080/api/users/me -H "Authorization: Bearer $(../../dev-idp/dev-token.sh customer)"`.

**Status:** in progress. See `../../CLAUDE.md` for the full spec and the implementation phases.
