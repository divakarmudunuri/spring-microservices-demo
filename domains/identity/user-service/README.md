# user-service

Users and addresses. Provisions users on their first Google/Okta login (just-in-time registration), exchanges external tokens for internal JWTs, and publishes the JWKS. In the `local` profile it also maps sign-ins from the dev identity provider (`../../../dev-idp/`) to the two seeded sample users. No passwords are stored.

| | |
|---|---|
| Port | 8082 |
| Storage | PostgreSQL `user_db` |

## Endpoints

- `POST /internal/auth/exchange` (gateway only): Google/Okta → just-in-time registration; dev-idp (`local` only) → the seeded sample user
- `GET /.well-known/jwks.json` (internal)
- `GET /api/users/me`, `PUT /api/users/me/address`, `GET /api/users/{id}`

## Implemented so far (phase 3)

- `GET /api/users/{id}`: profile, status and default address (`null` if none). Used by checkout and the order-details aggregator. Owner-or-admin check arrives in phase 11.
- Chaos toggles (`local` only): `demo.chaos.latency-ms`, `demo.chaos.failure-rate`, changed at runtime:
  ```bash
  curl -X POST localhost:8082/internal/chaos -H 'Content-Type: application/json' -d '{"latencyMs": 800}'
  curl localhost:8082/internal/chaos
  ```
  They affect `/api/**` only, never `/actuator/**` or `/internal/**`.

## Security (phase 11)

- `POST /internal/auth/exchange`: validates a Google / Okta / (local) dev-idp token itself, registers Google customers and Okta admins on their first sign-in, maps dev-idp sign-ins to the seeded sample users, and returns an internal JWT (RS256, 5 min). `GET /.well-known/jwks.json` publishes the key. Keys: `./scripts/generate-dev-keys.sh`.
- `GET /api/users/me`, `PUT /api/users/me/address` (CUSTOMER), `GET /api/admin/me` (ADMIN), `GET /api/users/{id}` (owner or ADMIN).
- Full design: `../../../docs/security.md`.

**Status:** in progress. See `../../../CLAUDE.md` for the full spec and the implementation phases. Data model: `../../../data-model/`.
