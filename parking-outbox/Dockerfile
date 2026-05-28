# Build from PROJECT ROOT: docker build -f parking-outbox/Dockerfile -t parking-outbox:latest .

# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM maven:3.9.9-eclipse-temurin-21 AS builder
WORKDIR /build

# Copy ALL pom.xml files so Maven reactor resolves correctly
COPY pom.xml .
COPY parking-api/pom.xml     parking-api/pom.xml
COPY parking-outbox/pom.xml  parking-outbox/pom.xml
COPY parking-worker/pom.xml  parking-worker/pom.xml

# Pre-download dependencies (layer cache — only invalidated when pom.xml changes)
RUN mvn -pl parking-outbox -am dependency:go-offline -q

# Copy source and build
COPY parking-outbox/src parking-outbox/src
RUN mvn -pl parking-outbox -am package spring-boot:repackage -DskipTests -q

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-jammy

RUN groupadd --gid 1001 appgroup \
 && useradd --uid 1001 --gid appgroup --no-create-home appuser

WORKDIR /app
COPY --from=builder /build/parking-outbox/target/parking-outbox-*.jar app.jar
RUN chown appuser:appgroup app.jar

USER appuser
EXPOSE 8081

ENV JAVA_OPTS="-XX:+UseZGC -XX:+ZGenerational -Xms128m -Xmx512m"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -Dserver.port=${PORT:-8081} -Dspring.profiles.active=${SPRING_PROFILES_ACTIVE:-prod} -jar app.jar"]
