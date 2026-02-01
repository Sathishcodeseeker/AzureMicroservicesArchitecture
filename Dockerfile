###############################################################################
# Stage 1 — BUILD
# Full JDK + Maven.  Only this stage touches source code.
###############################################################################
FROM eclipse-temurin:17-jdk-alpine AS builder

RUN apk add --no-cache maven

WORKDIR /build

# --- dependency cache layer ---------------------------------------------------
# Copy only pom.xml first.  Maven resolves all deps and caches them.
# If only Java source changes on the next build, this layer is reused.
COPY pom.xml ./
RUN mvn dependency:resolve --quiet

# --- compile + package --------------------------------------------------------
COPY src/ ./src/
RUN mvn package -DskipTests --quiet

# spring-boot-maven-plugin produces exactly this name (artifactId-version.jar)
RUN cp target/eventhub-outbox-1.0.0.jar /app.jar

###############################################################################
# Stage 2 — RUNTIME
# JRE only.  ~50 MB image.  No compiler, no Maven, no source.
###############################################################################
FROM eclipse-temurin:17-jre-alpine

# --- non-root user ------------------------------------------------------------
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

WORKDIR /app

COPY --from=builder /app.jar ./app.jar

# Spring Boot auto-reads YAML/properties from /app/config/ at runtime.
# The Kubernetes ConfigMap volume is mounted here (see deployment.yaml).
RUN mkdir -p config

EXPOSE 8080

# Mirrors the liveness probe in deployment.yaml.
HEALTHCHECK --interval=15s --timeout=5s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]