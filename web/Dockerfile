# SMSGate web dashboard: static SPA + same-origin reverse proxy to the API.
# Classic builder compatible (no BuildKit-only syntax) for CapRover.

FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
# The official entrypoint renders /etc/nginx/templates/*.template into
# /etc/nginx/conf.d at start. Only these variables are substituted, so nginx's
# own $variables survive.
# APK_DOWNLOAD_URL: where /downloads/smsgate.apk redirects (a fork points it
# at its own repository's releases).
ENV NGINX_ENVSUBST_FILTER="BACKEND_URL|NGINX_RESOLVER|APK_DOWNLOAD_URL" \
    BACKEND_URL=http://srv-captain--smsgate-api:8080 \
    NGINX_RESOLVER=127.0.0.11 \
    APK_DOWNLOAD_URL=https://github.com/jadliaissam/smsgate/releases/latest/download/smsgate.apk
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY nginx/security.conf nginx/proxy.conf /etc/nginx/snippets/
COPY --from=build /app/dist /usr/share/nginx/html
RUN mkdir -p /usr/share/nginx/downloads
# Git sha of the build (CI passes it), served at /revision.txt so a deploy can
# confirm the new image is the one serving. Last, so it doesn't bust the cache.
ARG REVISION=dev
RUN printf '%s\n' "$REVISION" > /usr/share/nginx/html/revision.txt
EXPOSE 80
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD wget -q --spider http://127.0.0.1/ || exit 1
