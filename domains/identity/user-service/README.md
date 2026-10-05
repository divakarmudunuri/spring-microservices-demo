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

**Status:** not implemented yet. See `../../../CLAUDE.md` for the full spec and the implementation phases. Data model: `../../../data-model/`.
