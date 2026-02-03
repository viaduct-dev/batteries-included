# Viaduct Integration Guide

This document explains how the Viaduct template architecture connects the frontend, GraphQL backend, and database layers.

## Architecture Overview

```
┌─────────────────┐         ┌─────────────────┐         ┌─────────────────┐
│                 │         │                 │         │                 │
│  React          │────────▶│  Viaduct        │────────▶│   Supabase      │
│  Frontend       │         │  GraphQL Layer  │         │   PostgreSQL    │
│  (Vite)         │         │  (Kotlin/Ktor)  │         │                 │
│                 │         │                 │         │                 │
└─────────────────┘         └─────────────────┘         └─────────────────┘
```

### Benefits of This Architecture

1. **Abstraction**: Frontend doesn't need to know about Supabase implementation details
2. **Type Safety**: Strongly-typed GraphQL schema with Kotlin resolvers
3. **Performance**: Batch resolution prevents N+1 query problems
4. **Modularity**: Schema organized for easy maintenance
5. **Flexibility**: Easy to add new data sources or change backends

## Project Structure

```
viaduct-template/
├── backend/                    # Viaduct GraphQL server
│   ├── src/main/
│   │   ├── kotlin/            # Application, resolvers, services
│   │   └── viaduct/schema/    # GraphQL schema files (.graphqls)
│   └── build.gradle.kts
├── src/                       # Frontend (React/Vite)
│   ├── lib/graphql.ts         # GraphQL client
│   ├── pages/                 # Page components
│   └── components/            # Reusable UI components
└── schema/                    # Database migrations
    └── migrations/            # SQL migration files
```

## Request Flow

1. **Frontend** makes GraphQL request to `http://localhost:8080/graphql`
2. **Viaduct Backend** authenticates via `Authorization` header
3. **Backend** creates authenticated Supabase client with user's JWT
4. **Supabase RLS** enforces row-level security policies
5. **Response** flows back through the same path

## Authentication

The frontend passes authentication headers to the backend:

```typescript
headers: {
  'Authorization': `Bearer ${accessToken}`,
  'X-User-Id': userId,
}
```

The backend verifies the token and creates an authenticated Supabase client that respects RLS policies.

## GraphQL Schema

Schema files are defined in `backend/src/main/viaduct/schema/*.graphqls`:

- Use `@resolver` directive to generate resolver base classes
- Use `@scope(to: ["default"])` for authenticated endpoints
- Use `@scope(to: ["public"])` for unauthenticated endpoints
- Types implement `Node` interface for GlobalID support

## Development Workflow

### Running All Services

```bash
# Using mise (recommended)
mise run dev

# Or manually:
# Terminal 1: Backend
cd backend && ./gradlew run

# Terminal 2: Frontend
npm run dev
```

### Testing GraphQL

Use GraphiQL at http://localhost:8080/graphiql to test queries interactively.

## Resources

- [AGENTS.md](./AGENTS.md) - Complete development documentation
- [Backend README](./backend/README.md) - Backend-specific details
- [IMPLEMENTING_A_RESOURCE.md](./docs/IMPLEMENTING_A_RESOURCE.md) - Adding new resources
