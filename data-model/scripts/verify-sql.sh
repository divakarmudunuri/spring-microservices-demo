#!/usr/bin/env bash
# Spins up a throwaway PostgreSQL 16 container, applies every schema + seed
# script in ../sql, then runs the constraint tests in ../sql/tests.
# Requires Docker. Nothing is left behind.
#   ./verify-sql.sh
set -euo pipefail
cd "$(dirname "$0")/../sql"

NAME=smd-sql-verify-$$
docker run -d --rm --name "$NAME" -e POSTGRES_PASSWORD=postgres postgres:16 >/dev/null
trap 'docker stop "$NAME" >/dev/null' EXIT
until docker exec "$NAME" pg_isready -U postgres >/dev/null 2>&1; do sleep 1; done
sleep 1

run() { # run <db> <user> <password> <file>
  docker exec -i -e PGPASSWORD="$3" "$NAME" psql -h localhost -U "$2" -d "$1" -v ON_ERROR_STOP=1 -q < "$4"
}

run postgres postgres postgres 00-create-databases.sql
while read -r n db user pw; do
  run "$db" "$user" "$pw" "$n-$db.sql"                     && echo "schema OK  $db"
  [[ -f "seed/$n-$db-seed.sql" ]] && run "$db" "$user" "$pw" "seed/$n-$db-seed.sql" && echo "seed OK    $db"
  [[ -f "tests/$db-tests.sql" ]] && run "$db" "$user" "$pw" "tests/$db-tests.sql" 2>&1 | grep -E 'PASS|FAIL'
done <<'DBS'
01 order_db order_svc order_local_pw
02 user_db user_svc user_local_pw
03 product_db product_svc product_local_pw
04 fulfillment_db fulfillment_svc fulfillment_local_pw
05 shipping_db shipping_svc shipping_local_pw
DBS
echo "All SQL verified."
