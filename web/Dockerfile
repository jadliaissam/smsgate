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
ENV NGINX_ENVSUBST_FILTER="BACKEND_URL|NGINX_RESOLVER" \
    BACKEND_URL=http://srv-captain--smsgate-api:8080 \
    NGINX_RESOLVER=127.0.0.11
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY nginx/security.conf nginx/proxy.conf /etc/nginx/snippets/
COPY --from=build /app/dist /usr/share/nginx/html
RUN mkdir -p /usr/share/nginx/downloads
EXPOSE 80
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD wget -q --spider http://127.0.0.1/ || exit 1
