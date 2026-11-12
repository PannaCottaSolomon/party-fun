# syntax=docker/dockerfile:1

# Events Service image.
#
#   docker build -t events:local .
#   docker compose up --build          (whole local stack, see compose.yaml)
#
# No secrets or credentials are baked in: DB_PASSWORD, RABBITMQ_PASSWORD etc. come from environment variables at
# runtime (see README "Configuration and secrets"). .dockerignore limits the build context to pom.xml and src/main/,
# so no .env / dev.env file, test code or build output can end up in the image.
# Tests are not run here: the CI pipeline runs them before it builds the image.

# ---------- build stage: Maven + JDK 21 (same Maven version as the wrapper in .mvn/wrapper) ----------
FROM maven:3.10.0-eclipse-temurin-21 AS build
WORKDIR /build

# Download dependencies first, so this layer stays cached until pom.xml changes
COPY pom.xml .
RUN mvn -B -q -ntp dependency:go-offline

# Build the executable jar, then split it into layers (dependencies change rarely, application code often), so a
# code change only rebuilds and pushes the small application layer
COPY src ./src
RUN mvn -B -q -ntp package -DskipTests -Djacoco.skip=true \
    && cp target/events-*.jar app.jar \
    && java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# ---------- runtime stage: JRE only ----------
FROM eclipse-temurin:21.0.12.1_1-jre-alpine

# Security patch the base image does not ship yet: brotli-libs 1.2.0-r1 has CVE-2026-111497 (CRITICAL, fixed in
# 1.2.1-r0), which fails the pipeline's Trivy gate. Remove this line once eclipse-temurin's image includes the fix.
RUN apk add --no-cache "brotli-libs>=1.2.1-r0"

# Run as an unprivileged user. The application files stay owned by root, so the process cannot modify them.
RUN addgroup -S events && adduser -S events -G events
WORKDIR /app
COPY --from=build /build/extracted/dependencies/ ./
COPY --from=build /build/extracted/spring-boot-loader/ ./
COPY --from=build /build/extracted/snapshot-dependencies/ ./
COPY --from=build /build/extracted/application/ ./
USER events

# Heap: up to 75% of the container's memory limit. Exit on OutOfMemoryError so the orchestrator restarts the
# container instead of leaving a broken JVM running.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080

# Liveness only (the JVM answers HTTP); database and broker availability is the readiness probe's job
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=3 \
    CMD wget -q -O /dev/null "http://127.0.0.1:${SERVER_PORT:-8080}/actuator/health/liveness" || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
