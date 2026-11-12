#!/bin/sh
# Container smoke test: boots the built image against a real MySQL and RabbitMQ on a private Docker network and checks
# that it becomes healthy, reaches both, and answers its API. Used by the release-image CI job before the image is
# pushed, so a broken image (bad entrypoint, missing class, failing startup) never reaches the registry.
#
#   sh ci/smoke-test.sh <image>
#
# Same structure as the Authentication, Events and User services' smoke tests.
# Stripe is never called: the service starts without a Stripe key (it only warns), so no CI secret is needed.
# Every credential below only exists inside the throw-away containers of this script.
set -eu

# Git Bash on Windows would otherwise rewrite arguments such as /dev/null into Windows paths.
export MSYS_NO_PATHCONV=1

IMAGE="${1:?usage: smoke-test.sh <image>}"
NETWORK="payment-smoke-$$"
PREFIX="payment-smoke-$$"
APP="$PREFIX-payment"

cleanup() {
    docker rm -f "$APP" "$PREFIX-mysql" "$PREFIX-rabbitmq" >/dev/null 2>&1 || true
    docker network rm "$NETWORK" >/dev/null 2>&1 || true
}
trap cleanup EXIT

fail() {
    echo "SMOKE TEST FAILED: $1"
    echo "----- payment container logs -----"
    docker logs "$APP" 2>&1 | tail -n 100 || true
    exit 1
}

# GET a path inside the container (busybox wget); prints the body
get() {
    docker exec "$APP" wget -q -O - "http://127.0.0.1:8080$1"
}

# HTTP status of a request made inside the container: http_status <path> [extra wget args...]
http_status() {
    path="$1"; shift
    docker exec "$APP" wget -q -S -O /dev/null "$@" "http://127.0.0.1:8080$path" 2>&1 \
        | grep -o 'HTTP/1\.[01] [0-9][0-9][0-9]' | tail -n 1 | cut -d ' ' -f 2
}

docker network create "$NETWORK" >/dev/null
docker run -d --name "$PREFIX-mysql" --network "$NETWORK" \
    -e MYSQL_DATABASE=payment -e MYSQL_USER=smoke -e MYSQL_PASSWORD=smoke -e MYSQL_ROOT_PASSWORD=smoke-root \
    mysql:8.4 >/dev/null
docker run -d --name "$PREFIX-rabbitmq" --network "$NETWORK" rabbitmq:3.13-management >/dev/null

echo "Waiting for MySQL..."
i=0
# -h 127.0.0.1 forces TCP: MySQL's temporary first-boot server has networking off, so this only passes for the real one
until docker exec "$PREFIX-mysql" mysqladmin ping -h 127.0.0.1 -usmoke -psmoke --silent >/dev/null 2>&1; do
    i=$((i + 1)); [ "$i" -le 90 ] || fail "MySQL did not start"; sleep 2
done

echo "Waiting for RabbitMQ..."
i=0
until docker exec "$PREFIX-rabbitmq" rabbitmq-diagnostics -q ping >/dev/null 2>&1; do
    i=$((i + 1)); [ "$i" -le 60 ] || fail "RabbitMQ did not start"; sleep 2
done

# stage=test: create the schema in this empty MySQL (create-drop). The image is the same one that ships; the default
# stage=dev only validates an existing schema, which a fresh database doesn't have.
echo "Starting $IMAGE..."
docker run -d --name "$APP" --network "$NETWORK" \
    -e stage=test \
    -e test_db_host="$PREFIX-mysql" -e db_user=smoke -e db_password=smoke \
    -e rabbitmq_host="$PREFIX-rabbitmq" \
    -e payment_admin_api_key=smoke-admin-key -e payment_service_api_key=smoke-service-key \
    "$IMAGE" >/dev/null

# 1. The image's own HEALTHCHECK (liveness) must report healthy
i=0
while :; do
    [ "$(docker inspect -f '{{.State.Running}}' "$APP")" = "true" ] || fail "container exited"
    status=$(docker inspect -f '{{.State.Health.Status}}' "$APP")
    [ "$status" = "healthy" ] && break
    i=$((i + 1)); [ "$i" -le 40 ] || fail "container did not become healthy (status: $status)"
    sleep 3
done
echo "HEALTHCHECK: healthy"

# 2. Overall health and readiness include MySQL and RabbitMQ (retry while connections settle)
i=0
until health=$(get /actuator/health/readiness) && echo "$health" | grep -q '"status":"UP"'; do
    i=$((i + 1)); [ "$i" -le 40 ] || fail "/actuator/health/readiness is not UP: ${health:-no answer}"
    sleep 3
done
echo "/actuator/health/readiness: $health"
health=$(get /actuator/health) || fail "/actuator/health did not answer"
echo "$health" | grep -q '"status":"UP"' || fail "/actuator/health is not UP: $health"
echo "/actuator/health: $health"

# 3. The API answers through the real database
payments=$(get /payments) || fail "GET /payments did not answer"
[ "$payments" = "[]" ] || fail "GET /payments returned: $payments"
echo "GET /payments: $payments"
bookings=$(get /bookings) || fail "GET /bookings did not answer"
[ "$bookings" = "[]" ] || fail "GET /bookings returned: $bookings"
echo "GET /bookings: $bookings"
# No stripe_publishable_key is set here, so the card-form settings must be refused with 503 (not 200 with no key)
code=$(http_status /payments/config)
[ "$code" = "503" ] || fail "GET /payments/config without a publishable key returned ${code:-no status}, expected 503"
echo "GET /payments/config without a publishable key: $code"

# 4. Protected operations stay protected in the shipped image
code=$(http_status /payments/1/refund --post-data='')
[ "$code" = "401" ] || fail "POST /payments/1/refund without a key returned ${code:-no status}, expected 401"
echo "POST /payments/1/refund without a key: $code"
code=$(http_status /payments/webhook/stripe --header='Content-Type: application/json' --post-data='{"id":"evt_smoke"}')
[ "$code" = "400" ] || fail "unsigned POST /payments/webhook/stripe returned ${code:-no status}, expected 400"
echo "unsigned POST /payments/webhook/stripe: $code"
code=$(http_status /actuator/env)
[ "$code" = "404" ] || fail "GET /actuator/env returned ${code:-no status}, expected 404 (only health is exposed)"
echo "GET /actuator/env: $code"

echo "SMOKE TEST PASSED"
