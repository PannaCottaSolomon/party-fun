# party.fun admin web: React + Vite, built to static files and served by nginx.
#
#   docker build -t partyfun-admin-web .
#   docker build --build-arg VITE_PAYMENT_API_URL=http://localhost:8080 \
#     --build-arg VITE_COGNITO_REGION=ap-southeast-1 --build-arg VITE_COGNITO_CLIENT_ID=<app client id> \
#     --build-arg VITE_USER_API_URL=http://localhost:8082 --build-arg VITE_API_BASE_URL=<api gateway url> \
#     -t partyfun-admin-web .
#   docker run --rm -p 8001:80 partyfun-admin-web
#
# VITE_* values are baked in at build time (the app runs in the browser). They are public URLs,
# never secrets.

# ---------- build stage ----------
FROM node:22-alpine AS build
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY . .
# The URLs and ids the *browser* uses (see .env.example).
ARG VITE_PAYMENT_API_URL=http://localhost:8080
ARG VITE_API_BASE_URL=
ARG VITE_COGNITO_REGION=
ARG VITE_COGNITO_CLIENT_ID=
ARG VITE_USER_API_URL=
ENV VITE_PAYMENT_API_URL=$VITE_PAYMENT_API_URL \
    VITE_API_BASE_URL=$VITE_API_BASE_URL \
    VITE_COGNITO_REGION=$VITE_COGNITO_REGION \
    VITE_COGNITO_CLIENT_ID=$VITE_COGNITO_CLIENT_ID \
    VITE_USER_API_URL=$VITE_USER_API_URL
RUN npm run build

# ---------- runtime stage ----------
FROM nginx:1.29-alpine

# Security patch the base image does not ship yet: brotli-libs 1.2.0-r0 has CVE-2026-111497 (CRITICAL, fixed in
# 1.2.1-r0), which fails the pipeline's Trivy gate. Remove this line once the nginx image includes the fix.
RUN apk add --no-cache "brotli-libs>=1.2.1-r0"
# The nginx image turns templates into /etc/nginx/conf.d/*.conf at start, filling in env vars (see the template).
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
ENV PAYMENT_UPSTREAM=http://payment:8080 \
    EVENTS_UPSTREAM=http://events:8080 \
    AUTH_UPSTREAM=http://authentication:8080
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
