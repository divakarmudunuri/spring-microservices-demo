#!/usr/bin/env bash
# Creates the DynamoDB tables in DynamoDB Local (default) or real AWS.
#   ./create-tables.sh                         -> DynamoDB Local on http://localhost:8000
#   ENDPOINT= ./create-tables.sh               -> real AWS (uses your AWS profile/region)
# The services also create these tables on startup in the local/docker profiles;
# this script exists for review and for manual setup.
set -euo pipefail
cd "$(dirname "$0")"

ENDPOINT="${ENDPOINT-http://localhost:8000}"
REGION="${AWS_REGION:-us-east-1}"
ARGS=(--region "$REGION")
if [[ -n "$ENDPOINT" ]]; then
  ARGS+=(--endpoint-url "$ENDPOINT")
  # DynamoDB Local accepts any credentials
  export AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID:-local}" AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY:-local}"
fi

for t in order_tracking carts; do
  if aws dynamodb describe-table --table-name "$t" "${ARGS[@]}" >/dev/null 2>&1; then
    echo "exists:  $t"
  else
    aws dynamodb create-table --cli-input-json "file://$t.table.json" "${ARGS[@]}" >/dev/null
    aws dynamodb wait table-exists --table-name "$t" "${ARGS[@]}"
    echo "created: $t"
  fi
  aws dynamodb update-time-to-live --table-name "$t" \
    --time-to-live-specification "Enabled=true,AttributeName=expiresAt" "${ARGS[@]}" >/dev/null 2>&1 || true
done
