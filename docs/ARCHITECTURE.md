# Architecture

## Overview

```
React Frontend (Vite)          Viaduct Backend (Kotlin/Ktor)       Supabase PostgreSQL
port 5173                      port 10000                          port 54321

  GraphQL queries/mutations       pg-persistence → pg_graphql          RLS policies
  Authorization: Bearer <jwt>     user credentials per request         enforce group membership
  X-User-Id: <uuid>
```

The frontend sends GraphQL requests to Viaduct. Its resolvers use pg-persistence to query and mutate
PostgreSQL through pg_graphql with the user's JWT. Viaduct checker executors and the existing database
policies enforce application access rules. Supabase Auth remains separate. See
[PG Persistence setup](PG_PERSISTENCE.md) for the library boundary, migrations and error behavior.

## Services

### Frontend — React/Vite

- **Local**: http://localhost:5173 (Vite dev server with HMR)
- **Production**: Static site on Render (free tier)
- **Source**: `src/` — components, pages, hooks, GraphQL client
- **Auth**: Stores Supabase JWT in memory, passes it to backend via headers
- **Config**: Fetches Supabase URL and anon key from backend at runtime (`supabaseConfig` query) so no credentials are hardcoded in the frontend build

### Backend — Viaduct GraphQL (Kotlin/Ktor)

- **Local**: http://localhost:10000/graphql (also serves GraphiQL at `/graphiql`)
- **Production**: Docker container on Render with CRaC sub-second restore
- **Source**: `backend/src/main/kotlin/com/viaduct/` (resolvers, services, plugins)
- **Schema**: `backend/src/main/viaduct/schema/*.graphqls`
- **Entry point**: `CracMain.kt` (CRaC path) or standard `embeddedServer` (non-CRaC path)
- **DI**: Koin standalone container (not a Ktor plugin — survives checkpoint/restore)

### Database — Supabase PostgreSQL

- **Local**: Runs in Podman containers via Supabase CLI
  - API: http://127.0.0.1:54321
  - PostgreSQL: `postgresql://postgres:postgres@127.0.0.1:54322/postgres`
  - Studio: http://127.0.0.1:54323
- **Production**: Hosted Supabase project (supabase.com)
- **Migrations**: `schema/migrations/*.sql` — applied automatically on deploy and by `mise run supabase-start` locally (via symlink at `supabase/migrations`)
- **Auth**: Supabase Auth with email provider. JWT expiry: 3600s. Email confirmation disabled for local dev.

## Tool Management — mise

[mise](https://mise.jdx.dev/) manages all tools and orchestrates development. Running `mise install` installs:

- **Java JDK 21** — Viaduct/Kotlin backend
- **Podman** — Container runtime for local Supabase
- **Supabase CLI** — Local Supabase management
- **Render CLI** — Production deployment inspection and management

Environment variables are set automatically in `mise.toml` — no manual exports needed. The
Supabase CLI version is pinned, and the wrapper bootstraps its native binary when the mise UBI
package contains only the launcher.

Key tasks:

| Task | What it does |
|------|-------------|
| `mise run dev` | Start everything (Podman + Supabase + backend + frontend) |
| `mise run deps-start` | Start Podman + Supabase only |
| `mise run backend` | Build and start backend (starts deps first) |
| `mise run frontend` | Start Vite dev server |
| `mise run test` | Run backend tests (starts Supabase automatically) |
| `mise run status` | Show Podman and Supabase status |
| `mise run stop` | Stop Supabase and Podman |
| `mise run diagnose-podman` | Debug Podman socket issues |
| `mise run get-docker-host` | Print the detected Podman API socket |

## CRaC Production Startup

Production uses [CRaC](https://openjdk.org/projects/crac/) with Azul Zulu Warp to snapshot the JVM heap after full initialization. At runtime, the application restores from the snapshot instead of doing a cold JVM start. CRaC is provided by the Azul JDK and does not depend on Docker-specific checkpoint support; `backend/Dockerfile` only packages the deployment.

## Database Migrations

Migrations live in `schema/migrations/` and are applied in two ways:

- **Locally**: `mise run supabase-start` starts the isolated local project and applies pending migrations from the `supabase/migrations` symlink
- **Production**: The Docker build's `migrations` stage runs them using `SUPABASE_SERVICE_ROLE_KEY` (passed as a build arg, never baked into the final image)

To reset locally: `.mise/scripts/supabase.sh db reset --workdir "$(pwd)"`

## Podman

Supabase CLI uses Docker-compatible containers. This project uses Podman instead of Docker. The `DOCKER_HOST` environment variable is auto-detected on macOS, Linux, and WSL via `.mise/scripts/get-podman-socket.sh`. The startup task also starts the API socket and recovers stopped project containers while preserving volumes.

The detector honors `CONTAINER_HOST`, asks Podman for its default local connection and native Linux service path, then falls back to the platform defaults.

The local Supabase project ID is `batteries-included`, so its containers cannot be mistaken for another checkout whose configuration directory is also named `supabase`.

### Troubleshooting

**Podman socket not found:**

```bash
mise run diagnose-podman    # Show socket paths and connectivity
podman machine stop && podman machine start   # Restart
podman machine init && podman machine start   # First time setup
```

On Linux and WSL, `mise run podman-start` starts a rootless user socket when one is not already available. Do not run mise with `sudo`.

**Backend won't start:**

1. `mise install` — ensure Java 21 and tools are installed
2. `mise run status` — ensure the Podman API and isolated Supabase project are healthy
3. Check `java -version` shows 21

**Database needs reset:**

```bash
.mise/scripts/supabase.sh db reset --workdir "$(pwd)"
```

## Deployment — Render.com

Defined in `render.yaml`. Two services:

- **viaduct-backend** — Docker web service (CRaC restore, port 10000)
- **viaduct-frontend** — Static site (Vite build output from `./dist`)

`ALLOWED_ORIGINS` and `VITE_GRAPHQL_ENDPOINT` are auto-configured via Render service linking. Only three credentials are needed: `SUPABASE_PROJECT_ID`, `SUPABASE_ANON_KEY`, `SUPABASE_SERVICE_ROLE_KEY`.

See [`SETUP_GUIDE.md`](SETUP_GUIDE.md) for step-by-step deployment instructions.
