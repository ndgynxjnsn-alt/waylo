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
COPY --from=build /src/gateway/build/libs/waylo-gateway.jar /app/app.jar
EXPOSE 8080
USER 10001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
