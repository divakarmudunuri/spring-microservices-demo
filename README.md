# spring-microservices-demo

An e-commerce order flow split into Spring Boot microservices. The full README (architecture, patterns tour, demo scripts) comes in phase 16; until then see `CLAUDE.md` and each service's `README.md`.

## Next steps

- **Real sign-in with Google and Okta (rest of phase 13).** The edge has been verified end to end with the dev identity provider (`dev-idp/`) only. To finish:
  1. Create the Google OAuth client (`nginx-proxy/README.md`) and the Okta app plus `smd-admins` group (`okta-login-setup/README.md`).
  2. Copy `nginx-proxy/.env.example` to `nginx-proxy/.env` and fill it in (never commit it).
  3. Start the edge without the dev-idp override: `cd nginx-proxy && docker compose --profile google --profile okta up -d`.
  4. Start the gateway and user-service with `GOOGLE_CLIENT_ID` and `OKTA_ISSUER_URI=https://<OKTA_DOMAIN>/oauth2/default`.
  5. Verify: anonymous browsing, a first Google sign-in registers a `CUSTOMER`, an Okta admin in `smd-admins` reaches `/admin`, an Okta user outside the group is refused, CSRF and blocked paths. Record the result in `nginx-proxy/README.md`.
- **Playwright end-to-end test for the storefront** (optional in CLAUDE.md 6.12): browse → guest cart → sign-in (dev-idp) → merge → checkout. The flow was verified by hand in phase 14; unit tests cover the interceptor, guards, session and cart store (`frontend/README.md`).
