#!/usr/bin/env bash
# A throwaway local CA and one leaf certificate for every origin the spike uses.
# Same-site pair:  app.vectis.localhost  +  iam.vectis.localhost   (eTLD+1 = vectis.localhost)
# Cross-site:      app.other.localhost                             (eTLD+1 = other.localhost)
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p tls && cd tls
[ -f leaf.pem ] && exit 0
openssl req -x509 -newkey rsa:2048 -nodes -days 30 -subj "/CN=vectis-spike-ca" \
  -keyout ca.key -out ca.pem 2>/dev/null
openssl req -newkey rsa:2048 -nodes -subj "/CN=vectis-spike" -keyout leaf.key -out leaf.csr 2>/dev/null
printf 'subjectAltName=DNS:app.vectis.localhost,DNS:iam.vectis.localhost,DNS:app.other.localhost,DNS:localhost\nextendedKeyUsage=serverAuth\n' > ext.cnf
openssl x509 -req -in leaf.csr -CA ca.pem -CAkey ca.key -CAcreateserial -days 30 \
  -extfile ext.cnf -out leaf.pem 2>/dev/null
keytool -importcert -noprompt -alias spike-ca -file ca.pem -keystore truststore.p12 \
  -storetype PKCS12 -storepass changeit >/dev/null
echo "tls/ ready"
