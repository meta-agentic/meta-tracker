#!/usr/bin/env bash
# VEC-54: runs Vectis on its own backlog. One command brings up a throwaway PostgreSQL, builds
# the web client and the server, starts the server with the vault import switched on, runs the
# import and leaves the server in the foreground; Ctrl-C stops it.
#
#   scripts/run-local.sh
#
# Needs Docker, JDK 25, Maven and Node (see CONTRIBUTING.md). The import only reads `.vault/`.
# Settings, all optional: VECTIS_PORT (8080), VECTIS_DB_PORT (5432), VECTIS_DB_PASSWORD
# (vectis, for the throwaway database only), VECTIS_LOCAL_DB (the container name).
set -euo pipefail

cd "$(dirname "$0")/.."

port="${VECTIS_PORT:-8080}"
db_container="${VECTIS_LOCAL_DB:-vectis-local-db}"
export VECTIS_DB_HOST=127.0.0.1
export VECTIS_DB_PORT="${VECTIS_DB_PORT:-5432}"
export VECTIS_DB_NAME=vectis
export VECTIS_DB_USER=vectis
export VECTIS_DB_PASSWORD="${VECTIS_DB_PASSWORD:-vectis}"
export QUARKUS_HTTP_PORT="$port"
export VECTIS_IMPORT_VAULT_ROOT="$PWD/.vault"

echo "== database ($db_container, postgres:16-alpine, 127.0.0.1:$VECTIS_DB_PORT)"
if [ -z "$(docker ps -aq -f "name=^${db_container}\$")" ]; then
  docker run -d --name "$db_container" \
    -e POSTGRES_USER="$VECTIS_DB_USER" -e POSTGRES_PASSWORD="$VECTIS_DB_PASSWORD" -e POSTGRES_DB="$VECTIS_DB_NAME" \
    -p "127.0.0.1:${VECTIS_DB_PORT}:5432" postgres:16-alpine >/dev/null
else
  docker start "$db_container" >/dev/null
fi
for _ in $(seq 60); do
  docker exec "$db_container" pg_isready -q -U "$VECTIS_DB_USER" && break
  sleep 1
done
docker exec "$db_container" pg_isready -q -U "$VECTIS_DB_USER" || { echo "the database did not start" >&2; exit 1; }

echo "== web client (workspace VEC)"
(cd web && { [ -d node_modules ] || npm ci; } && VITE_VECTIS_WORKSPACE_KEY=VEC npm run build)

echo "== server"
mvn -B -ntp -q -pl vectis-server -am package -DskipTests
java -jar vectis-server/target/quarkus-app/quarkus-run.jar &
server=$!
trap 'kill "$server" 2>/dev/null || true' EXIT INT TERM

for _ in $(seq 60); do
  curl -fsS -o /dev/null "http://localhost:$port/q/health/ready" 2>/dev/null && break
  kill -0 "$server" 2>/dev/null || { echo "the server stopped" >&2; exit 1; }
  sleep 1
done

echo "== import"
curl -fsS -X POST "http://localhost:$port/api/v1/imports/vault"
echo
echo
echo "Vectis is showing its own backlog at http://localhost:$port (Ctrl-C stops the server;"
echo "the database keeps its data, 'docker rm -f $db_container' removes it)."
wait "$server"
