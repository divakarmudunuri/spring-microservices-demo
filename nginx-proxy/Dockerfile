# The edge nginx, with the Angular app built in: one container serves the UI and
# fronts the API (/api -> gateway) and the sign-in flows (/oauth2 -> oauth2-proxy).
#
# The app comes from ../frontend through a named build context (docker-compose.yml:
# additional_contexts.frontend). The nginx config is mounted by docker-compose.yml, so
# config edits only need `nginx -s reload`; app changes need a rebuild:
#   docker compose up -d --build nginx

# --- 1. build the Angular app ------------------------------------------------
FROM node:24-alpine AS ui
WORKDIR /app
COPY --from=frontend package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY --from=frontend angular.json tsconfig.json tsconfig.app.json ./
COPY --from=frontend src ./src
COPY --from=frontend public ./public
RUN npx ng build --configuration production

# --- 2. nginx hosting it -------------------------------------------------------
FROM nginx:1.28-alpine
# drop the image's default pages (index.html, 50x.html): the folder holds only the app
RUN rm -rf /usr/share/nginx/html/*
COPY --from=ui /app/dist/storefront/browser /usr/share/nginx/html
