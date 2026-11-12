#!/bin/sh
set -eu

IMAGE="${1:?usage: smoke-test.sh <image>}"
NETWORK="auth-smoke-$$"
PREFIX="auth-smoke-$$"

cleanup() {
    docker rm -f "$PREFIX-auth" "$PREFIX-postgres" "$PREFIX-rabbitmq" >/dev/null 2>&1 || true
    docker network rm "$NETWORK" >/dev/null 2>&1 || true
}
trap cleanup EXIT

fail() {
    echo "SMOKE TEST FAILED: $1"
    echo "----- authentication container logs -----"
    docker logs "$PREFIX-auth" 2>&1 | tail -n 100 || true
    exit 1
}

docker network create "$NETWORK" >/dev/null
docker run -d --name "$PREFIX-postgres" --network "$NETWORK" \
    -e POSTGRES_PASSWORD=smoke -e POSTGRES_DB=authdb postgres:16-alpine >/dev/null
docker run -d --name "$PREFIX-rabbitmq" --network "$NETWORK" rabbitmq:3-management-alpine >/dev/null

echo "Waiting for PostgreSQL..."
i=0
until docker exec "$PREFIX-postgres" pg_isready -q -U postgres -d authdb; do
    i=$((i + 1)); [ "$i" -le 60 ] || fail "PostgreSQL did not start"; sleep 1
done

# No Cognito settings: the service must still start and report healthy without them (the Cognito bootstrap is
# skipped and /auth/* answers 503).
echo "Starting $IMAGE..."
docker run -d --name "$PREFIX-auth" --network "$NETWORK" \
    -e DB_HOST="$PREFIX-postgres" -e DB_PASSWORD=smoke \
    -e RABBITMQ_HOST="$PREFIX-rabbitmq" -e RABBITMQ_PASSWORD=guest \
    "$IMAGE" >/dev/null

i=0
while :; do
    status=$(docker inspect -f '{{.State.Health.Status}}' "$PREFIX-auth")
    [ "$status" = "healthy" ] && break
    [ "$(docker inspect -f '{{.State.Running}}' "$PREFIX-auth")" = "true" ] || fail "container exited"
    i=$((i + 1)); [ "$i" -le 40 ] || fail "container did not become healthy (status: $status)"
    sleep 3
done
echo "HEALTHCHECK: healthy"

i=0
until health=$(docker exec "$PREFIX-auth" wget -q -O - http://127.0.0.1:8080/actuator/health) \
        && echo "$health" | grep -q '"status":"UP"'; do
    i=$((i + 1)); [ "$i" -le 40 ] || fail "/actuator/health is not UP: ${health:-no answer}"
    sleep 3
done
echo "/actuator/health: $health"
echo "SMOKE TEST PASSED"
