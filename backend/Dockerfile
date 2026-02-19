# Multi-stage Dockerfile for Viaduct/Ktor backend with CRaC support
# Docker context is project root (.) to access schema/migrations
#
# Uses Azul Zulu with CRaC (Coordinated Restore at Checkpoint) to capture
# expensive Viaduct schema compilation in a checkpoint image, enabling
# near-instant startup on restore.
#
# Build stages:
#   1. builder    – Gradle build (produces installDist output)
#   2. checkpoint – Starts the CRaC entry point, compiles the Viaduct schema,
#                   and writes a CRaC checkpoint image to /app/cr
#   3. runtime    – Restores from checkpoint, reads live env vars, starts Ktor

# ── Stage 1: Build the application ──────────────────────────────────────
FROM azul/zulu-openjdk:21 AS builder

WORKDIR /app

# Copy Gradle wrapper and build files first (for better layer caching)
COPY backend/gradlew gradlew
COPY backend/gradle/ gradle/
COPY backend/build.gradle.kts build.gradle.kts
COPY backend/settings.gradle.kts settings.gradle.kts
COPY backend/gradle.properties gradle.properties

# Make gradlew executable
RUN chmod +x gradlew

# Download dependencies (cached layer)
RUN ./gradlew dependencies --no-daemon || true

# Copy source code (includes src/main/viaduct/ schema files)
COPY backend/src/ src/

# Copy Viaduct API files at root level
COPY backend/viaduct/ viaduct/

# Build the application distribution
RUN ./gradlew installDist --no-daemon -x test

# ── Stage 2: CRaC checkpoint ───────────────────────────────────────────
FROM azul/zulu-openjdk:21-jdk-crac AS checkpoint

WORKDIR /app

# Copy the distribution from builder stage
COPY --from=builder /app/build/install/viaduct-backend/ /app/

RUN chmod +x /app/bin/viaduct-backend

# Create the checkpoint directory
RUN mkdir -p /app/cr

# Take a CRaC checkpoint using the Warp engine (no CRIU, no privileges needed).
# The CracMain entry point:
#   1. Compiles the Viaduct GraphQL schema (expensive)
#   2. Sees -Dcrac.checkpoint and calls Core.checkpointRestore()
#   3. Warp snapshots the JVM state and the process exits
ENV JAVA_OPTS="-XX:CRaCEngine=warp -XX:CRaCCheckpointTo=/app/cr -Dcrac.checkpoint=true --add-opens java.base/java.lang=ALL-UNNAMED"
# The JVM exits with a non-zero code after a successful checkpoint (SIGKILL/137).
# Verify the checkpoint directory is non-empty to confirm it worked.
RUN /app/bin/viaduct-backend; \
    test "$(ls -A /app/cr)" || { echo "Checkpoint failed: /app/cr is empty"; exit 1; }

# ── Stage 3: Runtime (restore from checkpoint) ─────────────────────────
FROM azul/zulu-openjdk:21-jdk-crac

WORKDIR /app

# Install PostgreSQL client for migrations
RUN apt-get update && apt-get install -y --no-install-recommends \
    postgresql-client \
    && rm -rf /var/lib/apt/lists/*

# Copy the full application distribution (needed for classpath jars)
COPY --from=builder /app/build/install/viaduct-backend/ /app/

# Copy the CRaC checkpoint image
COPY --from=checkpoint /app/cr/ /app/cr/

# Copy migration files and script from project root
COPY schema/migrations/ /app/migrations/
COPY backend/scripts/run-migrations.sh /app/scripts/run-migrations.sh

# Make scripts executable
RUN chmod +x /app/scripts/run-migrations.sh

# Expose port (Render sets PORT env var, defaults to 8080)
EXPOSE 8080

# JVM options for container environment
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 --add-opens java.base/java.lang=ALL-UNNAMED"

# Restore from CRaC checkpoint (migrations run via Render preDeployCommand)
CMD ["java", "-XX:CRaCEngine=warp", "-XX:CRaCRestoreFrom=/app/cr"]
