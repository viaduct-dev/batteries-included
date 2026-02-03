# Rebuild Plan

This playbook explains how to rebuild an application with the same technology stack as **Viaduct Template** starting from an empty repository. Each section focuses on the concepts rather than one-off file names so the approach works in any environment. Use the GraphQL schema as the contract between layers, and keep the Viaduct global ID and policy guides (`VIADUCT_GLOBALID_GUIDE.md`, `VIADUCT_POLICY_GUIDE.md`) close at hand while you work.

---

## Step 1 — Workspace Foundations

1. Initialize a repo with two top-level projects: `backend/` (Kotlin + Viaduct) and `src/` (Vite + React).
2. Install prerequisites:
   - Java 21 (or JVM compatible with Viaduct).
   - Kotlin Gradle toolchain.
   - Node 18+ with npm or pnpm.
   - Supabase CLI and a local Postgres-compatible runtime (Docker/Podman).
3. Create `.env.local` files for frontend and backend. At minimum define:
   - `SUPABASE_URL`, `SUPABASE_ANON_KEY`, `SUPABASE_SERVICE_ROLE_KEY`.
   - `VITE_GRAPHQL_ENDPOINT`.
4. Decide on an environment orchestrator (e.g., `mise`, `docker-compose`, or shell scripts) to start Supabase, backend, and frontend together for day-to-day development.

---

## Step 2 — Schema-First Domain Design

1. Model the domain in GraphQL Schema Definition Language. The schema drives the rest of the implementation.
2. Create types that mirror the business entities:

```graphql
directive @requiresGroupMembership(groupIdField: String = "groupId") on FIELD_DEFINITION | OBJECT

type User @scope(to: ["default"]) {
  id: ID!
  email: String!
  isAdmin: Boolean!
  createdAt: String!
}

type Group implements Node
  @scope(to: ["default"])
  @requiresGroupMembership(groupIdField: "id") {
  id: ID!
  name: String!
  description: String
  ownerId: String!
  members: [GroupMember!]! @resolver
  resources: [Resource!]! @resolver
  createdAt: String!
  updatedAt: String!
}

type GroupMember @scope(to: ["default"]) {
  id: ID!
  groupId: String!
  userId: String!
  joinedAt: String!
}

type Resource implements Node
  @scope(to: ["default"])
  @requiresGroupMembership(groupIdField: "groupId") {
  id: ID!
  title: String!
  status: String!
  userId: String!
  groupId: String
  createdAt: String!
  updatedAt: String!
}

extend type Query @scope(to: ["default"]) {
  ping: String! @resolver
  groups: [Group!]! @resolver
  group(id: ID!): Group @resolver
  resources: [Resource!]! @resolver
  resourcesByGroup(groupId: ID!): [Resource!]! @resolver @requiresGroupMembership
  searchUsers(query: String!): [User!]! @resolver
}

extend type Query @scope(to: ["admin"]) {
  users: [User!]! @resolver
}

input CreateGroupInput {
  name: String!
  description: String
}

input AddGroupMemberInput {
  groupId: ID!
  userId: String!
}

input RemoveGroupMemberInput {
  groupId: ID!
  userId: String!
}

input CreateResourceInput {
  title: String!
  groupId: ID!
}

input UpdateResourceInput {
  id: ID!
  status: String
  title: String
}

input DeleteResourceInput {
  id: ID!
}

input SetUserAdminInput {
  userId: String!
  isAdmin: Boolean!
}

input DeleteUserInput {
  userId: String!
}

extend type Mutation @scope(to: ["default"]) {
  createGroup(input: CreateGroupInput!): Group! @resolver
  addGroupMember(input: AddGroupMemberInput!): GroupMember! @resolver
  removeGroupMember(input: RemoveGroupMemberInput!): Boolean! @resolver
  createResource(input: CreateResourceInput!): Resource! @resolver
  updateResource(input: UpdateResourceInput!): Resource! @resolver @requiresGroupMembership
  deleteResource(input: DeleteResourceInput!): Boolean! @resolver @requiresGroupMembership
}

extend type Mutation @scope(to: ["admin"]) {
  setUserAdmin(input: SetUserAdminInput!): Boolean! @resolver
  deleteUser(input: DeleteUserInput!): Boolean! @resolver
}
```

3. Re-run Viaduct code generation after every schema change (`./gradlew build`). This produces strongly-typed resolver stubs.
4. Follow the global ID guide to ensure every `ID` argument or field maps to `GlobalID<T>` in the Kotlin layer.

---

## Step 3 — Database & Row-Level Security

1. Use Supabase/Postgres to store the domain data. The minimal schema consists of:
   - `resources` table with optional `group_id`.
   - `groups` table owned by a user.
   - `group_members` table linking users and groups.
2. Create helper functions and triggers:
   - `is_admin()` reads `app_metadata.is_admin` from JWT.
   - `make_first_user_admin()` promotes the first registered user.
   - `is_group_member(group_id UUID)` and `is_group_owner(group_id UUID)` for access checks.
   - Timestamp triggers to keep `updated_at` fresh.
   - RPC helpers for `set_user_admin`, `get_all_users`, `delete_user_by_id`, `search_users`, and `cleanup_test_data`.
3. Define Row-Level Security policies mirroring the business rules:
   - Users can view resources only if they belong to the group or own the resource; admins bypass restrictions.
   - Group owners manage membership and groups; any member can view membership data.
   - Resource mutations require membership; admin rights extend to all groups.
4. Store the DDL in migration files under `schema/migrations/` so the database can be recreated anywhere.

---

## Step 4 — Backend Architecture (Kotlin + Viaduct)

1. Configure Gradle with Kotlin JVM, Ktor server, Viaduct runtime, Supabase client (`supabase-kt`), Koin for DI, and Jackson/Kotlin serialization.
2. Implement foundational services:
   - **SupabaseService**: verifies JWTs, instantiates user-scoped Supabase clients, and exposes RPC helpers.
   - **AuthService**: decodes JWTs locally, builds `GraphQLRequestContext`, selects schema (`admin` vs `default`), and hands out authenticated clients.
   - **GroupService**: orchestrates group CRUD, membership management, and membership checks.
   - **UserService**: wraps admin functions (list/search users, toggle admin, delete user).
3. Implement policy enforcement:
   - Register a `@requiresGroupMembership` directive in Schema.
   - Build a `GroupMembershipPolicyExecutor` that resolves the internal group ID from arguments or object data and verifies membership using `GroupService`.
   - Register the executor via a `CheckerExecutorFactory` in your Viaduct bootstrap.
4. Wire dependency injection with Koin (or another DI library):
   - Provide singletons for services and resolvers.
   - Supply a `TenantCodeInjector` that bridges Viaduct to Koin.
   - **Pass the Koin instance to your TenantCodeInjector** so it can resolve dependencies at runtime:

     ```kotlin
     // Get the Koin instance from the application context
     val koin = getKoin()

     // Pass it to the injector - don't rely on global context
     val koinInjector = KoinTenantCodeInjector(koin)
     ```

     Your `KoinTenantCodeInjector` should accept the Koin instance in its constructor rather than relying on implicit global state:

     ```kotlin
     class KoinTenantCodeInjector(private val koin: Koin) : TenantCodeInjector {
         override fun <T> getProvider(clazz: Class<T>): Provider<T> {
             return Provider {
                 val kClass = (clazz as Class<Any>).kotlin as KClass<Any>
                 koin.get(kClass, null) as T
             }
         }
     }
     ```
5. Implement resolvers generated by Viaduct:
   - Use `.internalID` when reading Global IDs.
   - Always fetch an authenticated Supabase client using the request context.
   - Map Supabase entities to GraphQL types with builder APIs and `ctx.globalIDFor`.
6. Host the GraphQL endpoint using Ktor:
   - Parse incoming requests, extract `Authorization: Bearer <token>`.
   - Build a `ViaductExecutionInput` with the proper schema ID.
   - Return JSON GraphQL responses and simple health/ping endpoints for diagnostics.

---

## Step 5 — Frontend Architecture (React + Vite)

1. Initialize a Vite React TypeScript project inside `src/`.
2. Configure the Supabase browser client:
   - Persist sessions in `localStorage`.
   - Auto-refresh tokens so GraphQL calls always have a valid JWT.
3. Build a GraphQL helper (`executeGraphQL`) that:
   - Waits for a Supabase session (retry if initialization is delayed).
   - Adds `Authorization: Bearer` and `X-User-Id` headers.
   - Throws on GraphQL errors and returns typed data.
4. Implement UI flows that reflect business logic:
   - **Group dashboard**: list groups, create new groups, navigate to group detail.
   - **Group detail**: show members and resources, add/remove members, add/update/delete resources.
   - **Admin console**: list all users, toggle admin status, delete users.
5. Adopt Tailwind or similar utility classes consistent with the project guidelines. Keep utilities near their elements and use `clsx`/`tailwind-merge` when class composition grows.
6. Gate UI routes based on authentication state and optionally admin privileges.

---

## Step 6 — Testing Strategy

1. **Database validation**:
   - Run database reset to ensure migrations apply cleanly.
   - Execute RPC functions manually (Supabase SQL console or `supabase functions invoke`) to confirm permissions.
2. **Backend tests**:
   - `./gradlew build` should regenerate schemas and compile resolvers without warnings.
   - Add unit tests for services (mock Supabase responses) and policy executors (membership success/failure, missing auth).
   - Add integration tests hitting the `/graphql` endpoint with mocked Supabase if feasible.
3. **Frontend tests**:
   - `npm run lint` to enforce ESLint rules.
   - `npm run build` for production bundles.
   - Implement Playwright specs covering: login flow, group CRUD, resource lifecycle, admin management, and authorization error displays. Run with `npm run test:e2e`.
4. **End-to-end smoke**:
   - Start Supabase, backend, and frontend together.
   - Seed two users (one admin, one regular). Walk through creating a group, inviting the second user, and asserting access controls.
   - Invoke the `cleanup_test_data` function between runs to keep tests deterministic.

---

## Step 7 — Operational Notes

1. Document environment variables and startup commands (`mise run dev`, individual `npm`/Gradle scripts, or Docker compose services).
2. Capture Supabase role keys in `.env.local` only; never commit secrets.
3. Provide a README summarizing:
   - Schema-driven architecture.
   - How policies enforce per-row membership.
   - How to run tests and refresh the database.
4. If deploying, package the backend as a Docker image and ensure migrations run on boot.

---

By following these steps, you can recreate the application's business behavior—shared group resources with membership policies, Supabase-powered auth, and a Viaduct GraphQL backend—without relying on the original repository. Adapt the structure to your organization's conventions while keeping the schema, RLS rules, and policy enforcement consistent so the business logic remains intact.
