# -----------------------------------------------------------------------------
# Stage 1: Build Spring Boot Executable JAR
# -----------------------------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-21 AS builder
WORKDIR /build

COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B package -DskipTests

# -----------------------------------------------------------------------------
# Stage 2: Playwright Java + Chromium Runtime
# Keep image tag version aligned with <playwright.version> in pom.xml
# -----------------------------------------------------------------------------
FROM mcr.microsoft.com/playwright/java:v1.60.0-noble

ENV CRAWLJ4AI_DATA_DIR=/data/.crawlj4ai \
    JAVA_OPTS="-XX:+UseZGC -XX:MaxRAMPercentage=75.0"

RUN mkdir -p /app /data/.crawlj4ai && chown -R pwuser:pwuser /app /data

WORKDIR /app
COPY --from=builder --chown=pwuser:pwuser /build/target/crawlj4ai-service-*.jar /app/app.jar

USER pwuser
VOLUME ["/data"]
EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
