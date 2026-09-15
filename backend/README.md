# Viaduct Template Backend

A Viaduct-based GraphQL backend service that sits between the React frontend and Supabase database.

## Architecture

This backend uses [Viaduct](https://github.com/airbnb/viaduct), a composable GraphQL server in Kotlin, to provide a GraphQL layer that:
- Abstracts Supabase implementation details
- Provides strongly-typed GraphQL schema
- Enables efficient batch resolution (N+1 query prevention)
- Supports modular schema organization

## Requirements

- Java JDK 21
- Environment variables for Supabase:
  - `SUPABASE_URL`: Your Supabase project URL
  - `SUPABASE_ANON_KEY`: Your Supabase anonymous key

## PG Persistence snapshot

`gradle.properties` selects the Viaduct and PG Persistence versions. Both the
`dev.viaduct.pg-persistence` plugin and `dev.viaduct.persistence:runtime` dependency
use `pgPersistenceVersion` and resolve from Maven Central's snapshot repository.
The runtime is packaged with the application; the plugin is available but not yet
applied. No local persistence checkout or
`mavenLocal()` publication is required.

This installs the snapshot from [PG Persistence PR #14](https://github.com/viaduct-dev/pg-persistence/pull/14),
including its batch-error, transaction, and union/interface support. See the
[library README](https://github.com/viaduct-dev/pg-persistence/blob/feat/union-interface-support/README.md)
for its APIs.

Dependency installation does not migrate the existing database calls or apply
generated migrations. Applying the plugin currently fails because `Group.members`
is resolver-backed rather than database-owned. The current resolvers still use
the Supabase database client; converting the schema, resolvers, and resource examples is tracked in
[KAN-21](https://viaduct-dev.atlassian.net/browse/KAN-21).

## Quick Start

### 1. Set environment variables

```bash
export SUPABASE_URL=https://your-project.supabase.co
export SUPABASE_ANON_KEY=your-anon-key
```

### 2. Build and run

```bash
./gradlew run
```

The server will start on `http://localhost:8080`.

### 3. Access GraphiQL

Open your browser to [http://localhost:8080/graphiql](http://localhost:8080/graphiql)

### 4. Try Example Queries

#### Get Supabase configuration (public)

```graphql
query {
  supabaseConfig {
    url
    anonKey
  }
}
```

#### Get all groups (authenticated)

```graphql
query {
  groups {
    id
    name
    description
  }
}
```

#### Create a group (authenticated)

```graphql
mutation {
  createGroup(input: {
    name: "My Group"
    description: "Optional description"
  }) {
    id
    name
  }
}
```

## Project Structure

```
backend/
├── src/main/
│   ├── kotlin/                 # Application, resolvers, services
│   │   └── com/viaduct/
│   │       ├── Application.kt  # Ktor entry point
│   │       ├── SupabaseClient.kt # Supabase integration
│   │       └── resolvers/      # GraphQL resolvers
│   └── viaduct/schema/         # GraphQL schema definitions (.graphqls)
└── build.gradle.kts            # Build configuration
```

## Development

### Build the project

```bash
./gradlew build
```

### Run tests

```bash
./gradlew test
```

### Clean build

```bash
./gradlew clean build
```

## Key Concepts

- **GlobalIDs**: Base64-encoded identifiers combining type name and internal ID
- **Scopes**: `@scope(to: ["default"])` for authenticated, `@scope(to: ["public"])` for unauthenticated
- **Resolvers**: Extend generated base classes and implement `resolve()` method
- **Authentication**: JWT token passed via `Authorization` header, creates authenticated Supabase client

## Adding New Features

See [IMPLEMENTING_A_RESOURCE.md](../docs/IMPLEMENTING_A_RESOURCE.md) for a complete guide to adding new resources.
