# ---- build stage -------------------------------------------------------------
FROM node:20-alpine AS build
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY . .
# Vite bakes VITE_API_URL into the bundle at BUILD time; changing it later needs a rebuild.
# Railway passes service variables to the build as build args.
ARG VITE_API_URL
ENV VITE_API_URL=$VITE_API_URL
RUN if [ -z "$VITE_API_URL" ]; then \
      echo "WARNING: VITE_API_URL build arg is empty; the app will rely on the runtime VITE_API_URL (see nginx/40-ledgerline-config.sh)"; \
    else \
      echo "Building with VITE_API_URL=$VITE_API_URL"; \
    fi
RUN npm run build

# ---- runtime stage -----------------------------------------------------------
FROM nginx:alpine
# The nginx image runs envsubst on /etc/nginx/templates/*.template at start; Railway overrides PORT.
ENV PORT=8080
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
# Writes /config.js from the runtime VITE_API_URL at container start. Strip CRLF (repo edited on Windows).
COPY nginx/40-ledgerline-config.sh /docker-entrypoint.d/40-ledgerline-config.sh
RUN sed -i 's/\r$//' /docker-entrypoint.d/40-ledgerline-config.sh && chmod +x /docker-entrypoint.d/40-ledgerline-config.sh
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080
