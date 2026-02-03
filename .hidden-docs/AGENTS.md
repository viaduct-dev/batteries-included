# AGENTS.md

This file provides guidance to AI coding assistants when working with code in this repository.

## Project Overview

This is a Viaduct template project with a three-tier architecture:
- **Frontend**: React/Vite with shadcn/ui components
- **Backend**: Viaduct GraphQL middleware layer (Kotlin/Ktor)
- **Database**: Supabase PostgreSQL

## Building a New Application

This template is designed as a starting point for building new applications. The typical workflow involves modifying all three layers: database schema, GraphQL backend, and React frontend.

### Step-by-Step Guide

1. **Define your data model** - Create SQL migrations in `schema/migrations/`
2. **Define GraphQL schema** - Add `.graphqls` files in `backend/src/main/viaduct/schema/`
3. **Implement resolvers** - Create Kotlin resolvers in `backend/src/main/kotlin/com/viaduct/resolvers/`
4. **Build frontend** - Create React components and GraphQL queries in `src/`

### Detailed Documentation

The following guides provide comprehensive instructions for each step:

| Document | Purpose |
|----------|---------|
| [`docs/IMPLEMENTING_A_RESOURCE.md`](docs/IMPLEMENTING_A_RESOURCE.md) | **Start here** - Complete walkthrough of implementing a new resource type across all layers |
| [`VIADUCT_GLOBALID_GUIDE.md`](VIADUCT_GLOBALID_GUIDE.md) | Working with Viaduct's GlobalID system in resolvers |
| [`VIADUCT_POLICY_GUIDE.md`](VIADUCT_POLICY_GUIDE.md) | Creating custom authorization policies with directives |
| [`INTEGRATION.md`](INTEGRATION.md) | Architecture overview and how the layers connect |
| [`backend/src/main/kotlin/com/viaduct/examples/`](backend/src/main/kotlin/com/viaduct/examples/) | Example resolver implementations with comments (use as templates) |

### Quick Reference: Adding a New Resource

**1. Database Migration** (`schema/migrations/YYYYMMDDHHMMSS_add_resource.sql`):
```sql
CREATE TABLE public.resources (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id UUID REFERENCES public.groups(id),
    user_id UUID REFERENCES auth.users(id),
    title TEXT NOT NULL,
    created_at TIMESTAMPTZ DEFAULT now()
);
ALTER TABLE public.resources ENABLE ROW LEVEL SECURITY;
```

**2. GraphQL Schema** (`backend/src/main/viaduct/schema/Resource.graphqls`):
```graphql
type Resource implements Node @scope(to: ["default"]) {
  id: ID!
  title: String!
  groupId: String
  createdAt: String!
}

extend type Query @scope(to: ["default"]) {
  resources: [Resource!]! @resolver
}

extend type Mutation @scope(to: ["default"]) {
  createResource(input: CreateResourceInput!): Resource! @resolver
}

input CreateResourceInput @scope(to: ["default"]) {
  title: String!
  groupId: ID @idOf(type: "Group")
}
```

**3. Kotlin Resolver** (`backend/src/main/kotlin/com/viaduct/resolvers/ResourcesQueryResolver.kt`):
```kotlin
@Resolver
class ResourcesQueryResolver : QueryResolvers.Resources() {
    override suspend fun resolve(ctx: Context): List<Resource> {
        val entities = ctx.authenticatedClient.getResources()
        return entities.map { entity ->
            Resource.Builder(ctx)
                .id(ctx.globalIDFor(Resource.Reflection, entity.id))
                .title(entity.title)
                .groupId(entity.group_id)
                .createdAt(entity.created_at)
                .build()
        }
    }
}
```

**4. Frontend Query** (`src/lib/graphql.ts`):
```typescript
export const GET_RESOURCES = `
  query GetResources {
    resources { id title groupId createdAt }
  }
`;
```

### Key Concepts

- **GlobalIDs**: Use `@idOf(type: "TypeName")` on input ID fields; access via `.internalID` in resolvers
- **Scopes**: Use `@scope(to: ["default"])` for authenticated access, `@scope(to: ["public"])` for unauthenticated
- **RLS Policies**: Database-level security using `is_group_member()` and `is_admin()` helpers
- **Resolvers**: Extend generated base classes and implement `resolve()` method

## Project Structure & Module Organization

- `src/` houses the React + Vite UI (components, pages, shared `lib/`, hooks) with global styles in `index.css`.
- `backend/` runs the Viaduct GraphQL service: Kotlin lives in `src/main/kotlin`, module schemas in each `viaduct/schema`, Gradle build files at the root.
- Database migrations sit in `schema/migrations/`.
- Local Supabase config sits in `supabase/`.
- Playwright specs land in `e2e/` with artifacts under `playwright-report/` and `test-results/`.

## Development Commands

### Using mise (Recommended)

This project uses [mise](https://mise.jdx.dev/) for tool management and orchestration:

```bash
# Install all dependencies (Java, Podman, Supabase CLI, etc.)
mise install

# Start full development environment (all services)
mise run dev

# Start only dependencies (Podman + Supabase)
mise run deps-start

# Start backend only (requires deps-start first)
mise run backend

# Start frontend only
mise run frontend

# Check status of all services
mise run status

# Stop all services
mise run stop
```

### Using npm (Frontend only)

```bash
npm install          # Install dependencies
npm run dev          # Start frontend dev server (port 5173)
npm run build        # Production build
npm run build:dev    # Development build
npm run lint         # Run ESLint
npm run preview      # Preview production build
```

### Using Gradle (Backend only)

```bash
cd backend
./gradlew run        # Start backend server (port 8080)
./gradlew build      # Build the project
./gradlew test       # Run tests
./gradlew clean build # Clean and rebuild
```

### Supabase (Database)

```bash
supabase start       # Start local Supabase
supabase stop        # Stop local Supabase
supabase status      # Check Supabase status
supabase db reset    # Reset database to migrations
```

## Architecture

### Request Flow

```
React Frontend (port 5173)
    ↓ GraphQL over HTTP
Viaduct Backend (port 8080)
    ↓ Supabase Kotlin Client
Supabase PostgreSQL (port 54321)
```

### Key Architecture Decisions

1. **Viaduct GraphQL Layer**: Provides abstraction between frontend and Supabase, enabling:
   - Type-safe GraphQL schema
   - Efficient batch resolution (prevents N+1 queries)
   - Modular schema organization
   - Backend flexibility (can swap data sources)

2. **Authentication**: Uses Supabase Auth. The frontend passes the access token and user ID in headers to the Viaduct backend.

3. **GraphQL Schema**: Uses camelCase field names (not snake_case like raw Supabase). Schema defined in `.graphqls` files under `backend/src/main/viaduct/schema/`.

## Code Organization

### Frontend (`src/`)

- `App.tsx`: Main app component with React Router setup
- `pages/Index.tsx`: Main page with CRUD operations
- `pages/Auth.tsx`: Authentication page
- `lib/graphql.ts`: GraphQL client with queries/mutations for Viaduct backend
- `integrations/supabase/`: Supabase client and TypeScript types
- `components/`: Reusable UI components (shadcn/ui based)
- `hooks/`: Custom React hooks

### Backend (`backend/`)

- `src/main/kotlin/com/viaduct/`:
  - `Application.kt`: Ktor application entry point
  - `SupabaseClient.kt`: Supabase client configuration
  - `resolvers/`: GraphQL resolvers for queries, mutations, and nodes
  - `plugins/`: Ktor plugins (authentication, routing, etc.)
- `src/main/viaduct/schema/`: GraphQL schema definitions (`.graphqls` files)
- `build.gradle.kts`: Gradle build configuration with Viaduct plugins

### Database Schema (`schema/`)

- `migrations/`: SQL migration files (database-agnostic)

### Supabase Config (`supabase/`)

- `config.toml`: Local Supabase development configuration

## Coding Style & Naming Conventions

- TypeScript stays in functional React components with 2-space indentation and strict typing. Components use `PascalCase`, hooks `useThing`, and helpers `camelCase`.
- Keep Tailwind utility strings near the elements they style; order classes layout → spacing → color and prefer `clsx`/`tailwind-merge` helpers.
- ESLint (`eslint.config.js`) enforces React Hooks and TypeScript rules—run `npm run lint` or enable on-save fixes in your editor.

## Environment Setup

### Environment Variables

All required environment variables are automatically managed by mise via `mise.toml`:

**Backend (Viaduct/Kotlin):**
- `SUPABASE_URL`: Local Supabase API endpoint
- `SUPABASE_ANON_KEY`: Supabase anonymous key

**Frontend (Vite/React):**
- `VITE_SUPABASE_URL`: Local Supabase URL for frontend (optional - fetched from backend if not set)
- `VITE_SUPABASE_PUBLISHABLE_KEY`: Supabase key for frontend (optional - fetched from backend if not set)
- `VITE_GRAPHQL_ENDPOINT`: Defaults to `http://localhost:8080/graphql` if not set

**Infrastructure:**
- `DOCKER_HOST`: Dynamically detected from Podman machine configuration
- The socket path is auto-detected at runtime, making the project portable across different machines

No manual exports needed - mise activates these automatically when you `cd` into the project directory.

### Tool Requirements

All required tools are managed by mise and installed via `mise install`:
- **Java JDK 21**: Required for Viaduct/Kotlin backend
- **Podman**: Container runtime for Supabase
- **Supabase CLI**: Local Supabase development
- **Node.js**: For frontend development (can also be managed via mise)

## GraphQL API

### Queries

```graphql
# Get Supabase configuration (public, no auth required)
query {
  supabaseConfig {
    url
    anonKey
  }
}

# Get all groups for authenticated user
query {
  groups {
    id
    name
    description
  }
}
```

### Mutations

```graphql
# Sign up (public, no auth required)
mutation {
  signUp(input: { email: "user@example.com", password: "password" }) {
    accessToken
    refreshToken
    user { id email }
  }
}

# Sign in (public, no auth required)
mutation {
  signIn(input: { email: "user@example.com", password: "password" }) {
    accessToken
    refreshToken
    user { id email }
  }
}

# Create group (requires auth)
mutation {
  createGroup(input: { name: "My Group", description: "Optional" }) {
    id
    name
  }
}
```

### Testing GraphQL

Access GraphiQL at http://localhost:8080/graphiql when backend is running.

## Service URLs (Local Development)

- **Frontend**: http://localhost:5173
- **Backend GraphQL**: http://localhost:8080/graphql
- **GraphiQL**: http://localhost:8080/graphiql
- **Supabase Studio**: http://127.0.0.1:54323
- **Supabase API**: http://127.0.0.1:54321
- **Local PostgreSQL**: postgresql://postgres:postgres@127.0.0.1:54322/postgres

## Important Implementation Details

### GlobalIDs

Viaduct uses GlobalIDs (base64-encoded identifiers combining type name and internal ID). The backend handles conversion between Supabase UUIDs and GlobalIDs.

### Authentication Flow

1. Frontend authenticates via GraphQL mutations (signIn/signUp) or Supabase Auth directly
2. Frontend sends GraphQL requests with headers:
   - `Authorization: Bearer <access_token>`
   - `X-User-Id: <user_id>`
3. Backend uses these for Supabase RLS policy enforcement

### Public Operations

The following operations don't require authentication:
- `supabaseConfig` query
- `signIn`, `signUp`, `refreshToken` mutations

### TypeScript Configuration

Project uses relaxed TypeScript settings for Lovable compatibility:
- `noImplicitAny: false`
- `strictNullChecks: false`
- Path alias `@/*` maps to `./src/*`

## Viaduct Module System

The backend uses Viaduct's modular architecture:
- Each module can have its own schema files and resolvers
- Schema files use `@resolver` directive to generate resolver base classes
- Types implement `Node` interface for GlobalID support
- Use `@scope(to: ["default"])` for tenant isolation
- Use `@scope(to: ["public"])` for unauthenticated access

## Testing Guidelines

- Add or update Playwright specs in `e2e/*.spec.ts` for meaningful UI flows; assert visible outcomes rather than implementation details.
- Place backend coverage in `backend/src/test/kotlin`; favor deterministic tests and stub Supabase access when live data is unnecessary.
- Run `npm run test:e2e` (plus `:ui`, `:headed`, `:debug`) for Playwright suites after UI-impacting work.

## Commit & Pull Request Guidelines

- Mirror existing history: short, imperative subjects with optional `scope:` prefixes (e.g., `backend: add group resolver`).
- Reference issues as `#123` and call out schema or migration impacts in the body.
- Before opening a PR, run lint, frontend build, the affected Playwright suite, and relevant Gradle tasks.

## Troubleshooting

### Backend won't start
1. Run `mise install` to ensure Java JDK 21 and all tools are installed
2. Check Java is available: `java -version` (should show version 21)
3. Verify environment variables are set
4. Ensure Supabase is running: `supabase status`

### Frontend can't connect to backend
1. Verify backend is running on port 8080
2. Check CORS configuration if needed
3. Verify `VITE_GRAPHQL_ENDPOINT` environment variable

### Podman issues

**Socket not found errors:**
If Supabase can't find the Podman socket, run the diagnostic:
```bash
mise run diagnose-podman
```

Common fixes:
```bash
# Restart Podman machine
podman machine stop
podman machine start

# If machine doesn't exist, initialize it
podman machine init
podman machine start

# Verify connection
podman info
```

The project now auto-detects the Podman socket location, so it should work across different machines without manual configuration.

### Database reset needed
```bash
mise run stop
supabase db reset
mise run dev
```

## Deployment to Render.com

This project includes a `render.yaml` blueprint for one-click deployment to [Render.com](https://render.com).

### Prerequisites

1. **Supabase Project**: Create a project at [supabase.com](https://supabase.com) (free tier works)
2. **Render Account**: Sign up at [render.com](https://render.com)

### Costs

- **Frontend**: Free (static site)
- **Backend**: Free (512MB RAM)
  - Spins down after ~15 min of inactivity
  - Cold starts take 30-60 seconds (JVM startup)
  - Upgrade to `starter` ($7/mo) in render.yaml for always-on

### One-Click Deploy

1. Click the "Deploy to Render" button in the README
2. Connect your GitHub repository
3. Enter your Supabase credentials when prompted (just 3 values!):
   - `SUPABASE_URL` - Your project URL
   - `SUPABASE_ANON_KEY` - Public/anon key
   - `SUPABASE_SERVICE_ROLE_KEY` - Service role key
4. Click "Apply" - Render handles the rest!

**What's automatic:**
- **Database migrations** run on every deploy
- **Frontend config** derived from backend credentials
- Backend and frontend URLs are auto-linked
- CORS is auto-configured between services

### Setting Up Supabase

1. Go to your [Supabase Dashboard](https://app.supabase.com)
2. Create a new project (or select existing)
3. **Enable Email Auth**: Go to **Authentication** → **Providers** → **Email** → Enable
4. **Get API credentials**: Go to **Settings** → **API** and copy:
   - **Project URL** → `SUPABASE_URL`
   - **anon public** key → `SUPABASE_ANON_KEY`
   - **service_role** key → `SUPABASE_SERVICE_ROLE_KEY`

### Database Migrations

**Migrations run automatically** on every deploy! The backend:
1. Derives the database connection from `SUPABASE_URL` + `SUPABASE_SERVICE_ROLE_KEY`
2. Runs all pending migrations from `schema/migrations/`
3. Tracks applied migrations in a `schema_migrations` table

For local development, use:
```bash
supabase db push
```

### Checking Configuration Status

After deployment, visit these endpoints:
- **Setup Status**: `https://viaduct-backend.onrender.com/setup` - Shows configuration status
- **Health Check**: `https://viaduct-backend.onrender.com/health` - Returns "OK" if running
- **GraphiQL**: `https://viaduct-backend.onrender.com/graphiql` - Interactive GraphQL explorer

### Service URLs (After Deployment)

- **Frontend**: `https://viaduct-frontend.onrender.com`
- **Backend GraphQL**: `https://viaduct-backend.onrender.com/graphql`

### Troubleshooting

**Services deployed but not working?**
1. Check `/setup` endpoint for configuration status
2. Verify all Supabase credentials are entered correctly
3. Ensure database migrations have been pushed

**First deploy is slow?**
The backend Docker build takes several minutes on first deploy. Subsequent deploys are faster due to caching.

## Environment & Security Notes

- Credentials in `mise.toml` support local work only; override via `.env.local` for personal secrets and avoid committing new keys.
- Default GraphQL endpoint is `http://localhost:8080/graphql`; update clients in `src/lib` when targeting staging or production.
