# Payment Service image.
#
#   docker build -t partyfun-payment .
#   docker run --rm -p 8080:8080 --env-file dev.env -e db_host=host.docker.internal \
#              -e rabbitmq_host=host.docker.internal partyfun-payment
#
# No secrets or credentials are baked in: everything comes from environment variables at runtime.

# ---------- build stage: Maven + JDK 21 ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Download dependencies first so this layer is cached until pom.xml changes.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q package -DskipTests

# ---------- runtime stage: JRE only ----------
FROM eclipse-temurin:21-jre-alpine


RUN apk add --no-cache "brotli-libs>=1.2.1-r0"


RUN addgroup -S payment && adduser -S payment -G payment
WORKDIR /app
COPY --from=build /build/target/payment.jar app.jar
USER payment

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 8080
# Liveness only (is the JVM serving HTTP?). Whether MySQL and RabbitMQ are reachable is /actuator/health/readiness,
# used by the CI smoke test and the Kubernetes readiness probe. wget comes with the Alpine base image.
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=3 \
    CMD wget -q -O /dev/null "http://127.0.0.1:${port:-8080}/actuator/health/liveness" || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
