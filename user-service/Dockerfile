FROM gradle:8-jdk21-alpine AS builder
WORKDIR /app

COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts ./

#RUN echo 'tasks.register("downloadDependencies") { \
#              doLast { \
#                  configurations \
#                      .filter { it.isCanBeResolved } \
#                      .forEach { configuration -> \
#                          configuration.resolve() \
#                      } \
#              } \
#          }' >> build.gradle.kts

RUN --mount=type=cache,target=/home/gradle/.gradle,sharing=private \
    --mount=type=cache,target=/root/.gradle,sharing=private \
    gradle --no-daemon downloadDependencies

COPY src ./src
RUN --mount=type=cache,target=/home/gradle/.gradle,sharing=private \
    --mount=type=cache,target=/root/.gradle,sharing=private \
    gradle shadowJar -x test --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/build/libs/*.jar app.jar
EXPOSE 8080
CMD ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]