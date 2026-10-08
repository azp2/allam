FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY .mvn .mvn
RUN mvn -B dependency:go-offline
COPY src src
COPY docs/openapi.json docs/openapi.json
RUN mvn -B package
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 journal && mkdir -p /app/var/files && chown -R journal /app
COPY --from=build /app/target/journal-backend-1.0.0-SNAPSHOT.jar app.jar
USER journal
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
