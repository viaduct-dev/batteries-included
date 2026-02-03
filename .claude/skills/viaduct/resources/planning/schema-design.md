# Schema Design Patterns

## Overview

Viaduct uses schema-first development. Your GraphQL schema is the source of truth - Viaduct generates type-safe Kotlin code from it. Proper schema design avoids refactoring later.

## Navigation

- Prerequisites: [Main Guide](../../viaduct.md)
- Related: [Task Breakdown](breakdown.md), [Entities](../core/entities.md)
- Next Steps: [Queries](../core/queries.md), [Mutations](../core/mutations.md)

## Schema-First Development Flow

```
1. Design GraphQL Schema (.graphqls files)
         |
         v
2. Run Viaduct Codegen (./gradlew generateViaduct...)
         |
         v
3. Implement Resolvers (Kotlin classes)
         |
         v
4. Test with GraphiQL
         |
         v
5. Iterate
```

## Core Schema Patterns

### Node Types (Entities)

Every entity that can be fetched by ID should implement `Node`:

```graphql
interface Node {
  id: ID!
}

type User implements Node @scope(to: ["default"]) {
  id: ID!
  firstName: String!
  lastName: String!
  email: String!
  createdAt: String!
}
```

**Guidelines:**
- Use `ID!` (not `String!`) for the id field
- Add `@scope` for visibility control
- Include audit fields (`createdAt`, `updatedAt`) if needed

### Input Types

Use dedicated input types for mutations:

```graphql
input CreateUserInput @scope(to: ["default"]) {
  firstName: String!
  lastName: String!
  email: String!
}

input UpdateUserInput @scope(to: ["default"]) {
  id: ID! @idOf(type: "User")  # CRITICAL: Use @idOf
  firstName: String            # Optional for partial updates
  lastName: String
  email: String
}
```

**Guidelines:**
- Always use `@idOf` on ID fields in inputs
- Make update fields optional for partial updates
- Separate create and update inputs (different required fields)

### Queries

```graphql
extend type Query @scope(to: ["default"]) {
  # Single entity by ID
  user(id: ID! @idOf(type: "User")): User @resolver

  # List all
  users: [User!]! @resolver

  # Filtered list
  usersByRole(role: String!): [User!]! @resolver

  # Paginated
  usersPage(limit: Int = 20, offset: Int = 0): UserConnection! @resolver
}
```

**Guidelines:**
- Always use `@idOf` on ID arguments
- Use `@resolver` directive for queries that fetch data
- Consider pagination for list queries

### Mutations

```graphql
extend type Mutation @scope(to: ["default"]) {
  createUser(input: CreateUserInput!): User! @resolver
  updateUser(input: UpdateUserInput!): User! @resolver
  deleteUser(id: ID! @idOf(type: "User")): Boolean! @resolver
}
```

**Guidelines:**
- Return the created/updated entity (not just Boolean)
- Use input types for complex arguments
- Name consistently: `createX`, `updateX`, `deleteX`

## Scope Patterns

### Basic Scopes

```graphql
# Authenticated users only
type User @scope(to: ["default"]) {
  id: ID!
  name: String!
}

# Admin-only fields
extend type User @scope(to: ["admin"]) {
  internalNotes: String
  createdBy: String
}

# Public (no auth)
type PublicProfile @scope(to: ["public"]) {
  displayName: String!
  avatarUrl: String
}
```

### Scope Organization

```graphql
# Base type with common fields
type Listing @scope(to: ["default", "public"]) {
  id: ID!
  title: String!
  description: String!
}

# Extended for authenticated users
extend type Listing @scope(to: ["default"]) {
  price: Float!
  hostId: String!
}

# Extended for admin
extend type Listing @scope(to: ["admin"]) {
  reviewStatus: String!
  moderationNotes: String
}
```

## Relationship Patterns

### One-to-Many

```graphql
type User implements Node {
  id: ID!
  posts: [Post!]! @resolver  # User has many posts
}

type Post implements Node {
  id: ID!
  authorId: String!           # Store the FK
  author: User @resolver      # Resolve to User node
}
```

### Many-to-Many

```graphql
type User implements Node {
  id: ID!
  groups: [Group!]! @resolver
}

type Group implements Node {
  id: ID!
  members: [User!]! @resolver
}

# Join table not exposed in GraphQL
# Database: user_groups(user_id, group_id)
```

### Self-Referencing

```graphql
type Category implements Node {
  id: ID!
  name: String!
  parentId: String
  parent: Category @resolver
  children: [Category!]! @resolver
}
```

## Field Design

### When to Use @resolver

```graphql
type User implements Node {
  # Core fields - fetched by Node Resolver
  id: ID!
  firstName: String!
  lastName: String!

  # Computed field - needs own resolver
  displayName: String @resolver

  # Different data source - needs own resolver
  posts: [Post!]! @resolver

  # Field with arguments - needs own resolver
  postsForYear(year: Int!): [Post!]! @resolver
}
```

### Nullable vs Non-Nullable

```graphql
type User implements Node {
  id: ID!           # Always present
  email: String!    # Required field
  bio: String       # Optional field (nullable)
  avatarUrl: String # Optional field
}

extend type Query {
  user(id: ID! @idOf(type: "User")): User  # Might not exist (nullable)
  users: [User!]!                           # List always returned, items non-null
}
```

### Field Naming

```graphql
# Good - clear, consistent
type Order {
  id: ID!
  customerId: String!       # FK field
  customer: Customer        # Relationship field
  totalAmount: Float!       # Descriptive
  createdAt: String!        # Timestamp
  isShipped: Boolean!       # Boolean prefix
}

# Avoid
type Order {
  id: ID!
  cust: String!             # Unclear abbreviation
  customer_id: String!      # Snake case
  total: Float!             # Ambiguous
  created: String!          # Ambiguous
  shipped: Boolean!         # Missing is/has prefix
}
```

## Common Patterns

### Pagination with Connections

```graphql
type UserConnection @scope(to: ["default"]) {
  items: [User!]!
  totalCount: Int!
  hasMore: Boolean!
  cursor: String
}

extend type Query {
  users(
    limit: Int = 20
    after: String  # Cursor-based
  ): UserConnection! @resolver
}
```

### Search/Filter

```graphql
input UserFilter @scope(to: ["default"]) {
  nameContains: String
  role: String
  createdAfter: String
  createdBefore: String
}

extend type Query {
  searchUsers(
    filter: UserFilter!
    limit: Int = 20
  ): [User!]! @resolver
}
```

### Enums

```graphql
enum OrderStatus @scope(to: ["default"]) {
  PENDING
  PROCESSING
  SHIPPED
  DELIVERED
  CANCELLED
}

type Order implements Node {
  id: ID!
  status: OrderStatus!
}
```

## Schema File Organization

```
backend/src/main/viaduct/schema/
├── common/
│   ├── Scalars.graphqls      # Custom scalars
│   └── Directives.graphqls   # Custom directives
├── entities/
│   ├── User.graphqls
│   ├── Post.graphqls
│   └── Group.graphqls
├── inputs/
│   ├── UserInputs.graphqls
│   └── PostInputs.graphqls
└── operations/
    ├── Queries.graphqls
    └── Mutations.graphqls
```

Or per-entity organization:
```
schema/
├── User.graphqls        # Type, inputs, queries, mutations
├── Post.graphqls
└── Group.graphqls
```

## Checklist for New Entity

- [ ] Type implements `Node` interface
- [ ] Type has `@scope` directive
- [ ] `id` field is `ID!` type
- [ ] Create input type with required fields
- [ ] Update input type with `@idOf` on ID field
- [ ] Query by ID with `@idOf` on argument
- [ ] List query (consider pagination)
- [ ] CRUD mutations with input types
- [ ] Relationship fields with `@resolver`

## See Also

- [Task Breakdown](breakdown.md) - Breaking down implementation
- [Entities](../core/entities.md) - Implementing resolvers
- [GlobalIDs](../gotchas/global-ids.md) - ID handling
