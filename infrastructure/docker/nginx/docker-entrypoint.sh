#!/bin/sh
set -e

# Log the KEYCLOAK_URL value for diagnostics
echo "[nginx-entrypoint] KEYCLOAK_URL=${KEYCLOAK_URL:-UNSET}"

# Replace ${KEYCLOAK_URL} in the SPA index.html
envsubst '${KEYCLOAK_URL}' < /usr/share/nginx/html/index.html.template > /usr/share/nginx/html/index.html

# Fail fast if substitution did not work
if grep -q '${KEYCLOAK_URL}' /usr/share/nginx/html/index.html; then
  echo "[nginx-entrypoint] ERROR: KEYCLOAK_URL was not substituted. Check env_file." >&2
  exit 1
fi
echo "[nginx-entrypoint] KEYCLOAK_URL substituted successfully"

# Floor PWA: inject KEYCLOAK_URL into the mobile SPA index.html
envsubst '${KEYCLOAK_URL}' < /usr/share/nginx/html/m/index.html.template > /usr/share/nginx/html/m/index.html
if grep -q '${KEYCLOAK_URL}' /usr/share/nginx/html/m/index.html; then
  echo "[nginx-entrypoint] ERROR: KEYCLOAK_URL not substituted in /m." >&2
  exit 1
fi

# Execute the CMD (nginx)
exec "$@"
