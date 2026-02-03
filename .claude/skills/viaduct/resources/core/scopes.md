# Scope-based API Visibility

## Overview

Viaduct uses `@scope` directives to control which API consumers can access specific types and fields. This enables building multi-tenant APIs where different clients see different schemas.

**⚠️ CRITICAL RULE: GraphQL `extend type` blocks MUST contain at least one field. Empty extension blocks cause build failures.**

## Navigation

- Prerequisites: [Entities](entities.md), [Queries](queries.md)
- Related: [Mutations](mutations.md)

## Scope Directive Syntax

```graphql
@scope(to: ["scope1", "scope2"])
```

**Common scopes:**
- `"default"` - Standard authenticated users
- `"admin"` - Admin-only features
- `"public"` - Unauthenticated access

## Basic Usage

### On Type Definitions

```graphql
# Available to default scope
type Tag implements Node @scope(to: ["default"]) {
  id: ID!
  name: String!
  color: String
}
```

### On Type Extensions (Different Scopes)

Use `extend type` to add fields visible only to specific scopes.

**⚠️ CRITICAL: Extension blocks MUST contain fields. Empty extensions cause build failures:**

```graphql
# ❌ BUILD ERROR - Empty extension block
extend type Tag @scope(to: ["admin"]) {
}

# ✅ CORRECT - Extension contains fields
extend type Tag @scope(to: ["admin"]) {
  internalNotes: String
  usageCount: Int @resolver
}
```

### On Query/Mutation Extensions

```graphql
# Default scope queries
extend type Query @scope(to: ["default"]) {
  tags: [Tag!]! @resolver
  tag(id: ID! @idOf(type: "Tag")): Tag @resolver
}

# Admin-only mutations
extend type Mutation @scope(to: ["admin"]) {
  deleteAllTags: Boolean! @resolver
  purgeInactiveTags(olderThanDays: Int!): Int! @resolver
}
```

### On Input Types

```graphql
input CreateTagInput @scope(to: ["default"]) {
  name: String!
  color: String
}

input AdminTagInput @scope(to: ["admin"]) {
  name: String!
  color: String
  internalNotes: String
}
```

## Complete Example: Adding Admin-Only Features

### Step 1: Define Base Type (default scope)

```graphql
# Tag.graphqls
type Tag implements Node @scope(to: ["default"]) {
  id: ID!
  name: String!
  color: String
  createdAt: String!
}

extend type Query @scope(to: ["default"]) {
  tags: [Tag!]! @resolver
  tag(id: ID! @idOf(type: "Tag")): Tag @resolver
}

extend type Mutation @scope(to: ["default"]) {
  createTag(input: CreateTagInput!): Tag! @resolver
  updateTag(input: UpdateTagInput!): Tag! @resolver
  deleteTag(id: ID! @idOf(type: "Tag")): Boolean! @resolver
}

input CreateTagInput @scope(to: ["default"]) {
  name: String!
  color: String
}

input UpdateTagInput @scope(to: ["default"]) {
  id: ID! @idOf(type: "Tag")
  name: String
  color: String
}
```

### Step 2: Add Admin-Only Extensions

```graphql
# Tag.graphqls (continued)

# Admin-only fields on Tag
extend type Tag @scope(to: ["admin"]) {
  internalNotes: String
  usageCount: Int @resolver
}

# Admin-only mutations
extend type Mutation @scope(to: ["admin"]) {
  deleteAllTags: Boolean! @resolver
  setTagInternalNotes(id: ID! @idOf(type: "Tag"), notes: String!): Tag! @resolver
}
```

### Step 3: Implement Admin Field Resolver

```kotlin
@Resolver("fragment _ on Tag { }")
class TagUsageCountResolver @Inject constructor(
    private val analyticsService: AnalyticsServiceClient
) : TagResolvers.UsageCount() {

    override suspend fun resolve(ctx: Context): Int {
        val tagId = ctx.objectValue.getId().internalID
        return analyticsService.getTagUsageCount(tagId)
    }
}
```

### Step 4: Implement Admin Mutation Resolver

```kotlin
@Resolver
class DeleteAllTagsResolver @Inject constructor(
    private val tagService: TagServiceClient
) : MutationResolvers.DeleteAllTags() {

    override suspend fun resolve(ctx: Context): Boolean {
        return tagService.deleteAll()
    }
}
```

### Multiple Scopes

A definition can be visible to multiple scopes:

```graphql
# Visible to both default and admin
type SharedConfig @scope(to: ["default", "admin"]) {
  setting: String!
}
```

## Schema Organization

Keep scope-related extensions organized in the same file:

```graphql
# Product.graphqls

# === DEFAULT SCOPE ===
type Product implements Node @scope(to: ["default"]) {
  id: ID!
  name: String!
  price: Float!
}

extend type Query @scope(to: ["default"]) {
  products: [Product!]! @resolver
}

# === ADMIN SCOPE ===
extend type Product @scope(to: ["admin"]) {
  costPrice: Float
  supplier: String
  lastRestocked: String
}

extend type Mutation @scope(to: ["admin"]) {
  bulkUpdatePrices(percentage: Float!): Int! @resolver
}
```

## Common Mistakes

### Empty extension block (BUILD ERROR)

```graphql
# ❌ BUILD ERROR - Empty extension blocks are invalid GraphQL
extend type Tag @scope(to: ["admin"]) {
}

# ✅ CORRECT - Always include fields in extensions
extend type Tag @scope(to: ["admin"]) {
  internalNotes: String
}
```

### Missing scope on type extension

```graphql
# WRONG - Missing @scope
extend type Tag {
  internalNotes: String
}

# CORRECT
extend type Tag @scope(to: ["admin"]) {
  internalNotes: String
}
```

### Inconsistent scopes between query and type

```graphql
# WRONG - Query in admin scope but type in default scope
type SecretData @scope(to: ["default"]) {  # Default users can't query this!
  secret: String!
}

extend type Query @scope(to: ["admin"]) {
  secretData: SecretData @resolver
}

# CORRECT - Match scopes appropriately
type SecretData @scope(to: ["admin"]) {
  secret: String!
}

extend type Query @scope(to: ["admin"]) {
  secretData: SecretData @resolver
}
```

## See Also

- [Entities](entities.md) - Node type definitions
- [Mutations](mutations.md) - Mutation implementation
- [GlobalIDs](../gotchas/global-ids.md) - ID handling in mutations
