# frontend

Angular storefront and admin UI for `spring-microservices-demo`.

**Status:** not scaffolded yet. It is built in phase 14 of the plan in `../CLAUDE.md` (section 6.12, "Frontend").

## Plan

- Angular (current stable major; confirm the version before scaffolding), project name **`storefront`**, standalone components, routing, strict TypeScript, SCSS.
- **Served by nginx** (`../nginx-proxy/`) at http://localhost. The build output `dist/storefront/browser` is mounted into nginx. Day-to-day: `npx ng build --watch` with the nginx stack running.
- Pages:
  - home, category/search, product detail, cart, checkout;
  - my orders (with the tracking timeline and "Confirm delivery");
  - admin area under **`/admin`**: orders, payments, shipments, inventory/restock. nginx only serves `/admin` to Okta-authenticated admins.
- **Sign-in happens at nginx, not in the app:**
  - customers: "Sign in with Google" → `/oauth2/customer/start?rd=<current page>` (the first sign-in registers the customer);
  - admins: Okta → `/oauth2/admin/start?rd=/admin`;
  - the app holds **no tokens**; the session is an HttpOnly cookie;
  - "am I signed in?" = `GET /api/users/me` (admin: `GET /api/admin/me`).
- The HTTP interceptor:
  - adds `X-Requested-With: XMLHttpRequest` to every API call (nginx's CSRF rule);
  - adds `X-Cart-Id` for a guest cart;
  - follows `loginUrl` on a `401`.
- The guest cart id is kept in `localStorage`. After sign-in, the guest cart is merged into the customer's cart via `POST /api/cart/merge`.
- Product images: placeholder SVGs in `public/products/<slug>.svg` (slugs listed in `../data-model/README.md`).

This folder is an npm project and is **not** part of the Gradle build.
