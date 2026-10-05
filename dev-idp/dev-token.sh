#!/usr/bin/env bash
# Prints an access token for a sample user from the dev identity provider,
# for calling the API without a browser. LOCAL DEVELOPMENT ONLY.
#
#   ./dev-token.sh customer       # sample-customer   (acts like a Google customer)
#   ./dev-token.sh admin          # sample-admin      (acts like an Okta admin in smd-admins)
#   ./dev-token.sh not-admin      # sample-not-admin  (Okta user WITHOUT the admin group)
#
#   TOKEN=$(./dev-token.sh customer)
#   curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/orders   # gateway directly
#
# The mock server accepts any password; only the username matters.
set -euo pipefail

BASE="${DEV_IDP_URL:-http://localhost:8099}"
case "${1:-}" in
  customer)  issuer=dev-customer; user=sample-customer  ;;
  admin)     issuer=dev-admin;    user=sample-admin     ;;
  not-admin) issuer=dev-admin;    user=sample-not-admin ;;
  *) echo "usage: $0 customer|admin|not-admin" >&2; exit 1 ;;
esac

response=$(curl -fsS "$BASE/$issuer/token" \
  -d grant_type=password \
  -d "username=$user" -d password=any \
  -d client_id=dev-client -d client_secret=dev-secret \
  -d scope="openid profile email")

if command -v jq >/dev/null; then
  echo "$response" | jq -r .access_token
else
  echo "$response" | python3 -c 'import json,sys;print(json.load(sys.stdin)["access_token"])'
fi
