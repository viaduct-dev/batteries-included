# Multi-stage Dockerfile for Viaduct/Ktor backend with CRaC support
# Docker context is project root (.) to access schema/migrations
#
# Uses Azul Zulu with CRaC (Coordinated Restore at Checkpoint) to capture
# Viaduct schema compilation AND Koin service initialization in a checkpoint
# image, enabling near-instant startup on restore.
#
# Build stages:
#   1. builder    – Gradle build (produces installDist output)
#   2. checkpoint – Compiles the Viaduct schema, initializes Koin with all
#                   singletons (services, resolvers, HTTP clients), and writes
#                   a CRaC checkpoint image to /app/cr
#   3. migrations – (optional) Runs DB migrations at build time if credentials
#                   are provided as build args. This stage is NOT part of the
#                   final image, so credentials never leak into image layers.
#   4. runtime    – Restores from checkpoint and starts Ktor/CIO (sub-second)

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

# Provide Supabase credentials so the checkpoint captures fully-initialized
# Koin singletons (SupabaseService, AuthService, HttpClient, etc.).
# These are consumed by CracMain during pre-checkpoint init.
# If not provided, the app still checkpoints but with dummy credentials.
ARG SUPABASE_ANON_KEY=""
ARG SUPABASE_PROJECT_ID=""
ARG SUPABASE_URL=""
ARG ALLOWED_ORIGINS=""
ENV SUPABASE_ANON_KEY=$SUPABASE_ANON_KEY
ENV SUPABASE_PROJECT_ID=$SUPABASE_PROJECT_ID
ENV SUPABASE_URL=$SUPABASE_URL
ENV ALLOWED_ORIGINS=$ALLOWED_ORIGINS

# Take a CRaC checkpoint using the Warp engine (no CRIU, no privileges needed).
# The CracMain entry point:
#   1. Compiles the Viaduct GraphQL schema (expensive)
#   2. Initializes Koin with all singletons (services, resolvers, HTTP clients)
#   3. Sees -Dcrac.checkpoint and calls Core.checkpointRestore()
#   4. Warp snapshots the JVM state and the process exits
ENV JAVA_OPTS="-XX:CRaCEngine=warp -XX:CRaCCheckpointTo=/app/cr -Dcrac.checkpoint=true --add-opens java.base/java.lang=ALL-UNNAMED"
# The JVM exits with a non-zero code after a successful checkpoint (SIGKILL/137).
# Verify the checkpoint directory is non-empty to confirm it worked.
RUN /app/bin/viaduct-backend; \
    test "$(ls -A /app/cr)" || { echo "Checkpoint failed: /app/cr is empty"; exit 1; }

# ── Stage 3: Run migrations at build time (optional) ─────────────────
# This stage runs migrations if SUPABASE_SERVICE_ROLE_KEY is provided as
# a build arg. The stage is ephemeral — credentials never appear in the
# final image since only a dummy marker file is copied out.
#
#   podman build --build-arg SUPABASE_SERVICE_ROLE_KEY=... \
#                --build-arg SUPABASE_PROJECT_ID=... ...
#
FROM ubuntu:22.04 AS migrations

RUN apt-get update && apt-get install -y --no-install-recommends \
    postgresql-client \
    && rm -rf /var/lib/apt/lists/*

COPY schema/migrations/ /app/migrations/
COPY backend/scripts/run-migrations.sh /app/scripts/run-migrations.sh
RUN chmod +x /app/scripts/run-migrations.sh

ARG SUPABASE_SERVICE_ROLE_KEY
ARG SUPABASE_PROJECT_ID
ARG SUPABASE_ANON_KEY
ARG SUPABASE_URL

# Run migrations if credentials are available; skip gracefully if not.
RUN if [ -n "$SUPABASE_SERVICE_ROLE_KEY" ]; then \
        echo "[Build] Running database migrations..."; \
        /app/scripts/run-migrations.sh; \
    else \
        echo "[Build] No SUPABASE_SERVICE_ROLE_KEY — skipping build-time migrations"; \
    fi

# Write a marker so the runtime stage can COPY from this stage,
# which forces Docker to actually execute it.
RUN date -u > /app/migrations-ran

# ── Stage 4: Runtime (restore from checkpoint) ─────────────────────────
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

# Force the migrations stage to run by copying its marker file.
# This is a no-op file — no credentials leak into this layer.
COPY --from=migrations /app/migrations-ran /tmp/migrations-ran

# Expose port (Render sets PORT env var, defaults to 8080)
EXPOSE 8080

# JVM options for container environment
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 --add-opens java.base/java.lang=ALL-UNNAMED"

# Restore from CRaC checkpoint
CMD ["java", "-XX:CRaCEngine=warp", "-XX:CRaCRestoreFrom=/app/cr"]
