# syntax=docker/dockerfile:1

# Build stage: compile the Spring Boot fat jar with the project's own Gradle wrapper.
# Build context is the repository root so the wrapper, version catalog and settings
# (which include the worker-sdk module) are all visible.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Copy build scripts first so the dependency layer caches across source changes.
COPY gradlew ./
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts ./
COPY engine/build.gradle.kts engine/build.gradle.kts
COPY worker-sdk/build.gradle.kts worker-sdk/build.gradle.kts
RUN ./gradlew --no-daemon :engine:dependencies > /dev/null 2>&1 || true

# Copy sources and build the boot jar (tests run in CI, not in the image build).
COPY engine/src engine/src
RUN ./gradlew --no-daemon :engine:bootJar -x test

# Run stage: minimal JRE, non-root, just the jar.
FROM eclipse-temurin:21-jre-alpine AS run
WORKDIR /app
RUN addgroup -S app && adduser -S -G app app
COPY --from=build /workspace/engine/build/libs/*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
