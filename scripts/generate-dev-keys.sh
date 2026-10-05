#!/usr/bin/env bash
# Creates the RSA key pair user-service signs internal JWTs with (CLAUDE.md 6.11):
#   .local/keys/jwt-private.pem   PKCS#8, never commit it (.local/ is git-ignored)
#   .local/keys/jwt-public.pem    X.509 SubjectPublicKeyInfo (also published as the JWKS)
# LOCAL DEVELOPMENT ONLY. Run once from anywhere; re-run with --force to replace the keys
# (tokens signed with the old key stop working).
set -euo pipefail
cd "$(dirname "$0")/.."

DIR=.local/keys
if [[ -f "$DIR/jwt-private.pem" && "${1:-}" != "--force" ]]; then
  echo "Keys already exist in $DIR (use --force to replace them)."
  exit 0
fi
mkdir -p "$DIR"
chmod 700 .local "$DIR"
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$DIR/jwt-private.pem" 2>/dev/null
openssl pkey -in "$DIR/jwt-private.pem" -pubout -out "$DIR/jwt-public.pem"
chmod 600 "$DIR/jwt-private.pem"
echo "Created $DIR/jwt-private.pem and $DIR/jwt-public.pem"
