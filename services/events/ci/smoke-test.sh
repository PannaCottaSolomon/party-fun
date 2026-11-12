#!/bin/sh
# Container smoke test: boots the built image against a real PostgreSQL and RabbitMQ on a private Docker network and
# checks that it becomes healthy and can reach both. Used by the build-image CI job before the image is pushed, so a
# broken image (bad entrypoint, missing class, failing startup) never reaches the registry.
#
#   sh ci/smoke-test.sh <image>
#
# The credentials below only exist inside the throw-away containers of this script.
set -eu

IMAGE="${1:?usage: smoke-test.sh <image>}"
NETWORK="events-smoke-$$"
PREFIX="events-smoke-$$"

cleanup() {
    docker rm -f "$PREFIX-events" "$PREFIX-postgres" "$PREFIX-rabbitmq" >/dev/null 2>&1 || true
    docker network rm "$NETWORK" >/dev/null 2>&1 || true
}
trap cleanup EXIT

fail() {
    echo "SMOKE TEST FAILED: $1"
    echo "----- events container logs -----"
    docker logs "$PREFIX-events" 2>&1 | tail -n 100 || true
    exit 1
}

docker network create "$NETWORK" >/dev/null
docker run -d --name "$PREFIX-postgres" --network "$NETWORK" \
    -e POSTGRES_PASSWORD=smoke -e POSTGRES_DB=events_db postgres:17 >/dev/null
docker run -d --name "$PREFIX-rabbitmq" --network "$NETWORK" rabbitmq:4.3-management >/dev/null

echo "Waiting for PostgreSQL..."
i=0
until docker exec "$PREFIX-postgres" pg_isready -q -U postgres -d events_db; do
    i=$((i + 1)); [ "$i" -le 60 ] || fail "PostgreSQL did not start"; sleep 1
done

echo "Starting $IMAGE..."
docker run -d --name "$PREFIX-events" --network "$NETWORK" \
    -e SPRING_DATASOURCE_URL="jdbc:postgresql://$PREFIX-postgres:5432/events_db" \
    -e DB_PASSWORD=smoke \
    -e SPRING_RABBITMQ_HOST="$PREFIX-rabbitmq" \
    -e RABBITMQ_PASSWORD=guest \
    -e SPRING_JPA_SHOW_SQL=false \
    "$IMAGE" >/dev/null

# 1. The image's own HEALTHCHECK (liveness) must report healthy
i=0
while :; do
    status=$(docker inspect -f '{{.State.Health.Status}}' "$PREFIX-events")
    [ "$status" = "healthy" ] && break
    [ "$(docker inspect -f '{{.State.Running}}' "$PREFIX-events")" = "true" ] || fail "container exited"
    i=$((i + 1)); [ "$i" -le 40 ] || fail "container did not become healthy (status: $status)"
    sleep 3
done
echo "HEALTHCHECK: healthy"

# 2. Overall health includes the database and the broker (RabbitMQ may still be starting, so retry)
i=0
until health=$(docker exec "$PREFIX-events" wget -q -O - http://127.0.0.1:8080/actuator/health) \
        && echo "$health" | grep -q '"status":"UP"'; do
    i=$((i + 1)); [ "$i" -le 40 ] || fail "/actuator/health is not UP: ${health:-no answer}"
    sleep 3
done
echo "/actuator/health: $health"

# 3. The API is up and protected: without a Cognito access token, GET /events must be refused with 401 (the smoke
#    test has no user pool to get a token from; step 2 already proved the database and broker are reachable)
answer=$(docker exec "$PREFIX-events" wget -q -O - http://127.0.0.1:8080/events 2>&1 || true)
echo "$answer" | grep -q " 401" || fail "GET /events without a token should be 401, got: ${answer:-no answer}"
echo "GET /events without a token: 401"

echo "SMOKE TEST PASSED"
