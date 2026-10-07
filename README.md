# Viaduct Template

A starter template demonstrating a three-tier architecture with GraphQL middleware.

## Deploy

[![Deploy to Render](https://render.com/images/deploy-to-render-button.svg)](https://dashboard.render.com/blueprints/new?repo=https://github.com/viaduct-dev/batteries-included&branch=render-template)

See the **[Setup Guide](docs/SETUP_GUIDE.md)** for step-by-step deployment instructions with screenshots, including how to get your Supabase credentials and configure Render.

---

## Architecture

```
React Frontend (Vite)
    │
    │ GraphQL
    ▼
Viaduct Backend (Kotlin/Ktor)
    │
    │ Supabase Client
    ▼
Supabase (PostgreSQL + Auth)
```

- **Frontend**: React + Vite with shadcn/ui components
- **Backend**: Viaduct GraphQL middleware (Kotlin/Ktor)
- **Database**: Supabase PostgreSQL with Row Level Security

## Local Development

```bash
# Install tools (requires mise: https://mise.jdx.dev)
mise install

# Start everything
mise run dev
```

Services:
- Frontend: http://localhost:5173
- GraphQL API: http://localhost:8080/graphql
- GraphiQL: http://localhost:8080/graphiql
- Supabase Studio: http://127.0.0.1:54323

## Documentation

Administrator-only fields and object types use `@requiresAdmin`, defined in
`backend/src/main/viaduct/schemabase/Authorization.graphqls`. For example:

```graphql
deleteUser(input: DeleteUserInput!): Boolean! @resolver @requiresAdmin
```

Viaduct's checker enforces this using the verified request identity. Schema scopes
control visibility, not authorization; Supabase policies and group checks remain in place.

See [CLAUDE.md](./CLAUDE.md) for complete documentation including:
- Development commands
- Architecture details
- GraphQL API reference
- Troubleshooting guide

## Costs

- **Supabase**: Free tier (500MB database, 50K auth users)
- **Render Frontend**: Free (static site)
- **Render Backend**: Free (512MB RAM, spins down after inactivity)
  - Upgrade to Starter ($7/mo) for always-on
