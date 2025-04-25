FROM gradle:8-jdk21-alpine AS builder
ARG SERVICE
WORKDIR /app

RUN echo ">> Building the application ${SERVICE}"

COPY ${SERVICE}/build.gradle.kts     build.gradle.kts
COPY ${SERVICE}/settings.gradle.kts  settings.gradle.kts
COPY ${SERVICE}/gradle                gradle
COPY common /common

RUN --mount=type=cache,target=/home/gradle/.gradle,sharing=private \
    --mount=type=cache,target=/root/.gradle,sharing=private \
    gradle --no-daemon downloadDependencies

COPY ${SERVICE}/src                  src

RUN --mount=type=cache,target=/home/gradle/.gradle,sharing=private \
    --mount=type=cache,target=/root/.gradle,sharing=private \
    gradle shadowJar -x test --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/build/libs/*.jar app.jar
EXPOSE 8080
CMD ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]