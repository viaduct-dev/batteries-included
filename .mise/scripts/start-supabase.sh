#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
SUPABASE_WORKDIR="${SUPABASE_WORKDIR:-$PROJECT_ROOT}"
PROJECT_ID="$(sed -n 's/^project_id = "\([^"]*\)"/\1/p' "$SUPABASE_WORKDIR/supabase/config.toml")"
test -n "$PROJECT_ID"
DB_CONTAINER="supabase_db_$PROJECT_ID"
GATEWAY_CONTAINER="supabase_kong_$PROJECT_ID"
PHASE="Podman startup"

on_error() {
    local status=$?
    trap - ERR
    echo
    echo "$PHASE failed. Collecting diagnostics..." >&2
    bash "$SCRIPT_DIR/diagnose-local-deps.sh" || true
    exit "$status"
}
trap on_error ERR

bash "$SCRIPT_DIR/start-podman.sh"

DOCKER_HOST="$(bash "$SCRIPT_DIR/get-podman-socket.sh" 2>/dev/null)"
export DOCKER_HOST
echo "Using DOCKER_HOST: $DOCKER_HOST"

podman_api() {
    podman --remote --url "$DOCKER_HOST" "$@"
}

# Recover this project's containers after an interrupted Podman or WSL shutdown.
DB_STATE="$(podman_api inspect --format '{{.State.Status}}' "$DB_CONTAINER" 2>/dev/null || true)"
if [ -n "$DB_STATE" ] && [ "$DB_STATE" != "running" ]; then
    PHASE="Supabase stale-container cleanup"
    echo "Found stale batteries-included containers (database: $DB_STATE)."
    "$SCRIPT_DIR/supabase.sh" stop --workdir "$SUPABASE_WORKDIR"
fi

PHASE="Supabase startup"
"$SCRIPT_DIR/supabase.sh" start --workdir "$SUPABASE_WORKDIR" --exclude vector,logflare

PHASE="Local database migrations"
"$SCRIPT_DIR/supabase.sh" migration up --local --workdir "$SUPABASE_WORKDIR"
podman_api exec "$DB_CONTAINER" \
    psql -U postgres -d postgres -c "NOTIFY pgrst, 'reload schema';" >/dev/null

DB_STATE="$(podman_api inspect --format '{{.State.Status}}' "$DB_CONTAINER" 2>/dev/null || true)"
GATEWAY_STATE="$(podman_api inspect --format '{{.State.Status}}' "$GATEWAY_CONTAINER" 2>/dev/null || true)"
if [ "$DB_STATE" != "running" ] || [ "$GATEWAY_STATE" != "running" ]; then
    echo "Supabase core containers are not healthy:" >&2
    echo "  database: ${DB_STATE:-missing}" >&2
    echo "  gateway: ${GATEWAY_STATE:-missing}" >&2
    false
fi

echo "Local Supabase is ready."
