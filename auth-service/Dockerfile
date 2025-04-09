FROM openjdk:17-jdk-alpine

RUN apk add --no-cache bash

WORKDIR /app

COPY . .

COPY --from=gradle:7.3-jdk17 /usr/local /usr/local

RUN ./gradlew build

ENTRYPOINT ["./gradlew", "run"]

EXPOSE 8080
