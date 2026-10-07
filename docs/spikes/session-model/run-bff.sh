#!/usr/bin/env bash
# Starts one replica of the BFF prototype. Every replica shares VECTIS_SESSION_KEY, which is
# what lets a session cookie minted by one replica be read by another (D9).
#   ./run-bff.sh <https-port> [extra env assignments…]
set -euo pipefail
cd "$(dirname "$0")"
port=$1; shift
export VECTIS_SSL_PORT=$port
export VECTIS_TLS_CERT=$PWD/fixture/tls/leaf.pem VECTIS_TLS_KEY=$PWD/fixture/tls/leaf.key
export VECTIS_TRUSTSTORE=$PWD/fixture/tls/truststore.p12
export VECTIS_CLIENT_SECRET=spike-client-secret-not-for-production
export VECTIS_SESSION_KEY=${VECTIS_SESSION_KEY:-spike-session-key-0123456789abcdef0123456789}
for kv in "$@"; do export "$kv"; done
exec java -Dquarkus.http.port=$((port - 400)) -jar target/quarkus-app/quarkus-run.jar
