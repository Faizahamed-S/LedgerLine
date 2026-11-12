#!/bin/sh
# Runs at container start (the official nginx image executes /docker-entrypoint.d/*.sh).
# Writes the backend URL from the RUNTIME environment into /config.js, so the app works even if VITE_API_URL was
# not passed as a build argument. Railway injects service variables into the running container.
set -eu

api_url="$(printf '%s' "${VITE_API_URL:-}" | tr -d '"\\<>')"
printf 'window.__LEDGERLINE_CONFIG__ = { apiUrl: "%s" };\n' "$api_url" > /usr/share/nginx/html/config.js

if [ -n "$api_url" ]; then
  echo "ledgerline: runtime API base URL: $api_url"
else
  echo "ledgerline: VITE_API_URL is not set at runtime; the build-time value (if any) will be used"
fi
