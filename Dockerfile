# G16: the image run locally is the image deployed. Dependency layer is
# cached separately so code-only rebuilds skip re-downloads.
FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml ./
COPY .mvn/ ./.mvn/
COPY mvnw ./
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw
RUN ./mvnw -B -ntp dependency:go-offline
COPY src/ ./src/
RUN ./mvnw -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre-jammy AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -r -u 1001 appuser
WORKDIR /app
RUN mkdir -p /app/target/logs && chown -R appuser:appuser /app
COPY --from=build --chown=appuser:appuser /app/target/book-my-seat-*.jar app.jar
USER appuser
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=65 -XX:+UseSerialGC -XX:TieredStopAtLevel=1"
HEALTHCHECK --interval=10s --timeout=3s --start-period=45s --retries=3 \
  CMD curl -fsS http://localhost:${PORT:-8080}/health/live || exit 1
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]