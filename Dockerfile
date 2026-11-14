# syntax=docker/dockerfile:1
# One build, two images:
#   docker build --target gateway  -t waylo-gateway .
#   docker build --target dhl-mock -t waylo-dhl-mock .

# ---- build -------------------------------------------------------------------------------
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
RUN --mount=type=cache,target=/root/.gradle ./gradlew --version -q
COPY protocol ./protocol
COPY dhl-mock ./dhl-mock
COPY gateway ./gateway
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon -q :gateway:bootJar :dhl-mock:installDist -x test

# ---- DHL mock ------------------------------------------------------------------------------
FROM eclipse-temurin:25-jre AS dhl-mock
WORKDIR /app
COPY --from=build /src/dhl-mock/build/install/dhl-mock /app
EXPOSE 9999/udp
USER 10001
ENTRYPOINT ["/app/bin/dhl-mock"]

# ---- gateway -------------------------------------------------------------------------------
FROM eclipse-temurin:25-jre AS gateway
WORKDIR /app

# Observability (Milestone 2), dormant unless attached via JAVA_TOOL_OPTIONS
# (see compose.observability.yml):
#  * OpenTelemetry Java agent - zero-code traces, metrics and logs over OTLP
#  * Pyroscope OTel extension - bundles the Pyroscope profiler (async-profiler) and
#    labels profiles with the active span; no separate Pyroscope javaagent needed.
ARG OTEL_AGENT_VERSION=2.33.1
ARG PYROSCOPE_OTEL_VERSION=2.1.4
ADD --chmod=644 https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar /otel/opentelemetry-javaagent.jar
ADD --chmod=644 https://github.com/grafana/otel-profiling-java/releases/download/v${PYROSCOPE_OTEL_VERSION}/pyroscope-otel-javaagent-extension.jar /otel/pyroscope-otel.jar
RUN chmod 755 /otel
COPY --from=build /src/gateway/build/libs/waylo-gateway.jar /app/app.jar
EXPOSE 8080
USER 10001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
