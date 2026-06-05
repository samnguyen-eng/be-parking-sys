# Build from PROJECT ROOT: docker build -f parking-api/Dockerfile -t parking-api:latest .

# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM maven:3.9.9-eclipse-temurin-21 AS builder
WORKDIR /build

# Copy ALL pom.xml files so Maven reactor resolves correctly
COPY pom.xml .
COPY parking-api/pom.xml     parking-api/pom.xml
COPY parking-worker/pom.xml  parking-worker/pom.xml

# Pre-download dependencies (layer cache — only invalidated when pom.xml changes)
RUN mvn -pl parking-api -am dependency:go-offline -q

# Copy source and build
COPY parking-api/src parking-api/src
RUN mvn -pl parking-api -am package spring-boot:repackage -DskipTests -q

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-jammy

RUN groupadd --gid 1001 appgroup \
 && useradd --uid 1001 --gid appgroup --no-create-home appuser

WORKDIR /app
COPY --from=builder /build/parking-api/target/parking-api-*.jar app.jar
RUN chown appuser:appgroup app.jar

USER appuser
EXPOSE 8080

# Cloud Run injects PORT env var; we pass it via JAVA_OPTS
# Container-aware JVM settings — no fixed -Xmx, let JVM use MaxRAMPercentage
# G1GC: better fit for small containers vs ZGC (ZGC optimal for large heaps)
# MaxRAMPercentage=55 → heap ~1.1Gi of 2Gi, leaving ~900Mi for native/gRPC/threads
ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=55 -XX:InitialRAMPercentage=25 -XX:MaxMetaspaceSize=160m -XX:ReservedCodeCacheSize=96m -Xss512k -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -Dserver.port=${PORT:-8080} -jar app.jar"]
