# frontend

Angular storefront and admin UI for `spring-microservices-demo` (CLAUDE.md 6.12).

- **Angular 21.2 (LTS)**, project name `storefront`, standalone components, zoneless with signals, strict TypeScript, SCSS.
- **Unit tests:** Vitest (Angular 21's default runner).
- **npm project:** not part of the Gradle build. `node_modules/` and `dist/` are git-ignored (and kept out of the nginx image build by `.dockerignore`).

## Run it

The app is **built into and hosted by the edge nginx** (`../nginx-proxy/Dockerfile`: `npm ci` + `ng build` on this folder, then `nginx:1.28-alpine`). It's served at http://localhost, where sign-in works and every API call goes through nginx → gateway → services. The edge compose file builds the image for you; after app changes, run `cd ../nginx-proxy && docker compose up -d --build nginx`.

Once:

```bash
npm ci
```

**Rebuild-on-save loop** (still through nginx): run the watch build, and start the edge with the `ui-watch` override, which makes nginx serve your local `dist/storefront/browser` instead of the built-in app:

```bash
npx ng build --watch
```

```bash
cd ../nginx-proxy && docker compose -f docker-compose.yml -f docker-compose.ui-watch.yml up -d
```

To try it without Google or Okta accounts, add the `dev-idp` override (`../dev-idp/README.md`) and sign in as `sample-customer` or `sample-admin`.

**Pure UI work:** `npm start` serves on http://localhost:4200 with `proxy.conf.json`, which forwards `/api` and `/oauth2` to nginx on http://localhost. Cookies are per host, not per port, so a session made on http://localhost also works on :4200. After a sign-in, oauth2-proxy sends you back to `localhost` (port 80).

```bash
npm test          # unit tests (Vitest), once: npx ng test --watch=false
npx ng build      # production build into dist/storefront/browser
```

## Pages

| Route | Who | What |
|---|---|---|
| `/` | anyone | home from the BFF (`/api/storefront/home`): categories, featured, new arrivals |
| `/category/:slug`, `/search?q=` | anyone | paged product grid with sorting (`/api/products`) |
| `/product/:slug` | anyone | details, availability level, add to cart, related products |
| `/cart` | anyone | quantities, remove, subtotal; guests are sent to sign-in before checkout |
| `/checkout` | customer | shipping address (add/change), wallet balance and top-up, place order |
| `/orders`, `/orders/:id` | customer | order list; details with shipment, tracking timeline, "Confirm delivery" |
| `/admin/orders`, `/admin/orders/:id` | admin | all orders (filter by status / customer) and any order's details |
| `/admin/payments` | admin | totals by status and all payments |
| `/admin/shipments` | admin | all shipments |
| `/admin/inventory` | admin | exact stock with restock |

Product pages are under `/product/…`, not `/products/…`, because nginx serves the placeholder images from `/products/<slug>.svg` (`public/products/`).

## How it fits the security design

- **No tokens in the browser.** Sign-in is a full-page trip through nginx + oauth2-proxy (`/oauth2/customer/start?rd=…`, `/oauth2/admin/start?rd=…`), and the session is an HttpOnly cookie. "Am I signed in?" = `GET /api/users/me` (admin: `GET /api/admin/me`); a 401 there just means "no". Code: `core/session.ts`, `core/login-redirect.ts`.
- **The HTTP interceptor** (`core/api.interceptor.ts`):
  - adds `X-Requested-With: XMLHttpRequest` to every `/api` call (nginx's CSRF rule);
  - adds `X-Cart-Id` to cart calls while a guest cart exists;
  - follows the `loginUrl` of a 401 ProblemDetail from nginx, returning to the current page.
- **Guest cart → customer cart** (`core/cart-store.ts`):
  - The first "Add to cart" creates a guest cart. Its id, a bearer secret, goes in `localStorage`, never in a cookie.
  - On the first page load after sign-in, the app calls `POST /api/cart/merge` and then forgets the guest id.
  - Pages that show the cart wait for this, so they never show the pre-merge cart.
- **Guards** (`core/guards.ts`) are convenience only. nginx, the gateway and every service enforce access on their own. A 403 (for example a suspended account) is shown as an error, not treated as "signed out", which would loop through the login.

## Things the UI makes visible

- **Degraded responses:** the BFF home, the order details aggregator and the cart all say which part is missing ("prices temporarily unavailable", "Some information is temporarily unavailable: shipping") instead of failing. Try it with product-service's chaos toggle (`POST localhost:8083/internal/chaos {"failureRate":1.0}`, `local` profile).
- **Eventual consistency:**
  - The order page refreshes every 3 s while fulfillment and shipping run over Kafka, and until the tracking timeline has the event that closes the order's status.
  - After an admin restock, the storefront's availability level updates a moment later (`inventory-events`).
  - After checkout, the cart empties a moment later, when cart-service sees `ORDER_CONFIRMED`.
- **ProblemDetail errors** (`core/problem.ts`): the `title` and `detail` are shown as they are, e.g. "Out of stock" or "Insufficient funds". A rejected checkout links to the rejected order, which is still tracked.

## Not done (yet)

- The Playwright end-to-end test (browse → guest cart → sign-in → merge → checkout) from CLAUDE.md 6.12 is optional ("if time allows") and not written. That flow was checked by hand through nginx with the dev-idp in phase 14.
