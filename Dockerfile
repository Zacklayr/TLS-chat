FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B -DskipTests dependency:go-offline
COPY src src
RUN mvn -B package -DskipTests

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /workspace/target/classes target/classes
COPY --from=build /workspace/target/dependency target/dependency
COPY photos photos
RUN mkdir -p /app/uploads && useradd --create-home --uid 10001 appuser && chown -R appuser:appuser /app
USER appuser
ENV PORT=8080
ENV FILE_STORAGE_DIR=/app/uploads
EXPOSE 8080
ENTRYPOINT ["java", "-cp", "target/classes:target/dependency/*", "kz.zacklayr.tlschat.ChatServer"]
