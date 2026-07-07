# syntax=docker/dockerfile:1

# Build stage: compile the sample worker distribution with the project's Gradle wrapper.
# Build context is the repository root so the wrapper, version catalog, settings and the
# shared protocol module are all visible.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Copy build scripts first so the dependency layer caches across source changes.
COPY gradlew ./
COPY gradle gradle
# Be resilient to Windows checkouts (CRLF / missing exec bit) so the wrapper runs.
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew
COPY settings.gradle.kts build.gradle.kts ./
COPY protocol/build.gradle.kts protocol/build.gradle.kts
COPY engine/build.gradle.kts engine/build.gradle.kts
COPY worker-sdk/build.gradle.kts worker-sdk/build.gradle.kts
RUN ./gradlew --no-daemon :worker-sdk:dependencies > /dev/null 2>&1 || true

# Copy sources (worker-sdk depends on the shared protocol module) and install the app.
COPY protocol/src protocol/src
COPY worker-sdk/src worker-sdk/src
RUN ./gradlew --no-daemon :worker-sdk:installDist -x test

# Run stage: minimal JRE, non-root, the generated start script + libs.
FROM eclipse-temurin:21-jre-alpine AS run
WORKDIR /app
RUN addgroup -S app && adduser -S -G app app
COPY --from=build /workspace/worker-sdk/build/install/worker-sdk /app
USER app
ENTRYPOINT ["/app/bin/worker-sdk"]
