#!/usr/bin/env bash
# Starts the identity provider the spike signs in against: Tessera, built from source
# (TESSERA_JAR, a `-Dquarkus.profile=prod,singlenode` uber-jar), in its standalone
# single-tenant mode (ADR-IAM-002), over TLS, with a throwaway Postgres in Docker.
#
#   TESSERA_JAR=/path/to/tessera-server-…-runner.jar ./start-tessera.sh
#
# Hosts: add `127.0.0.1 app.vectis.localhost iam.vectis.localhost app.other.localhost`
# to /etc/hosts (the JVM does not resolve *.localhost on its own).
set -euo pipefail
cd "$(dirname "$0")"
: "${TESSERA_JAR:?set TESSERA_JAR to a prod,singlenode Tessera runner jar}"
./certs.sh
docker rm -f vectis-spike-iam-db >/dev/null 2>&1 || true
docker run -d --name vectis-spike-iam-db -p 5433:5432 \
  -e POSTGRES_DB=iam -e POSTGRES_USER=iam -e POSTGRES_PASSWORD=iam \
  -v "$PWD/roles.sql:/docker-entrypoint-initdb.d/roles.sql:ro" postgres:17-alpine >/dev/null
until docker exec vectis-spike-iam-db pg_isready -U iam -d iam >/dev/null 2>&1; do sleep 1; done
sleep 2

ISSUER=https://iam.vectis.localhost:9443
export TESSERA_FIXED_TENANT=0199a3b0-0000-7000-8000-000000000001
export TESSERA_OIDC_ISSUER=$ISSUER IAM_KEYS_ISSUER=$ISSUER
export IAM_KEYS_MASTER_KEY="$(openssl rand -base64 32)"
export TESSERA_BOOTSTRAP_ENABLED=true
export TESSERA_BOOTSTRAP_CLIENT_ID=vectis-bff
export TESSERA_BOOTSTRAP_CLIENT_SECRET=spike-client-secret-not-for-production
export TESSERA_BOOTSTRAP_CLIENT_REDIRECT_URIS=https://app.vectis.localhost:8443/auth/callback,https://app.other.localhost:8443/auth/callback
export TESSERA_BOOTSTRAP_USER_USERNAME=alice TESSERA_BOOTSTRAP_USER_PASSWORD=spike-password-1
# A BFF behind a TLS-terminating edge cannot present a client certificate (finding F2).
export TESSERA_REQUIRE_SENDER_CONSTRAINT="${TESSERA_REQUIRE_SENDER_CONSTRAINT:-false}"
# Both app origins may call /login, so the cross-site case fails on the cookie, not on CORS.
export TESSERA_CORS_ORIGINS=https://app.vectis.localhost:8443,https://app.other.localhost:8443
export QUARKUS_DATASOURCE_USERNAME=iam_app QUARKUS_DATASOURCE_PASSWORD=iam_app
export QUARKUS_DATASOURCE_REACTIVE_URL=postgresql://localhost:5433/iam
export QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://localhost:5433/iam
export QUARKUS_FLYWAY_USERNAME=iam_migrator QUARKUS_FLYWAY_PASSWORD=iam_migrator
export QUARKUS_HTTP_PORT=9080 QUARKUS_HTTP_SSL_PORT=9443
export QUARKUS_HTTP_SSL_CERTIFICATE_FILES=$PWD/tls/leaf.pem QUARKUS_HTTP_SSL_CERTIFICATE_KEY_FILES=$PWD/tls/leaf.key
export TESSERA_RATELIMIT_ENABLED=false
exec java -jar "$TESSERA_JAR"
