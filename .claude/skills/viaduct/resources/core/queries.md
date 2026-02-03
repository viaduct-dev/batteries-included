# Query Implementation

## Overview

Query fields are the entry points to your GraphQL API. They typically fetch data from external services and return node types. Use batch resolution to avoid N+1 query problems.

## Navigation

- Prerequisites: [Entities](entities.md)
- Related: [GlobalIDs](../gotchas/global-ids.md), [Relationships](relationships.md)
- Next Steps: [Mutations](mutations.md)

## Query Field Definition

Define query fields by extending the `Query` type:

```graphql
extend type Query @scope(to: ["default"]) {
  # Single entity by ID
  user(id: ID! @idOf(type: "User")): User @resolver

  # List of entities
  users: [User!]! @resolver

  # Filtered list
  usersByRole(role: String!): [User!]! @resolver
}
```

**Critical**: Always use `@idOf` on ID arguments to get proper GlobalID deserialization. See [GlobalID Guide](../gotchas/global-ids.md) for complete patterns.

## Query Resolver Implementation

### Basic Query Resolver

```kotlin
@Resolver
class UserQueryResolver @Inject constructor(
    private val userService: UserServiceClient
) : QueryResolvers.User() {

    override suspend fun resolve(ctx: Context): User? {
        // ctx.arguments.id is GlobalID<User> (thanks to @idOf)
        val userId = ctx.arguments.id.internalID

        val data = userService.fetch(userId)
            ?: return null

        return User.Builder(ctx)
            .id(ctx.globalIDFor(User.Reflection, data.id))
            .firstName(data.firstName)
            .lastName(data.lastName)
            .build()
    }
}
```

### List Query Resolver

```kotlin
@Resolver
class UsersQueryResolver @Inject constructor(
    private val userService: UserServiceClient
) : QueryResolvers.Users() {

    override suspend fun resolve(ctx: Context): List<User> {
        val users = userService.fetchAll()

        return users.map { data ->
            User.Builder(ctx)
                .id(ctx.globalIDFor(User.Reflection, data.id))
                .firstName(data.firstName)
                .lastName(data.lastName)
                .build()
        }
    }
}
```

### Query with Arguments

```graphql
extend type Query {
  usersByRole(role: String!, limit: Int): [User!]! @resolver
}
```

```kotlin
@Resolver
class UsersByRoleQueryResolver @Inject constructor(
    private val userService: UserServiceClient
) : QueryResolvers.UsersByRole() {

    override suspend fun resolve(ctx: Context): List<User> {
        val role = ctx.arguments.role
        val limit = ctx.arguments.limit ?: 100

        val users = userService.fetchByRole(role, limit)

        return users.map { data ->
            User.Builder(ctx)
                .id(ctx.globalIDFor(User.Reflection, data.id))
                .firstName(data.firstName)
                .lastName(data.lastName)
                .build()
        }
    }
}
```

## Batch Resolution

### The N+1 Problem

Consider this query:

```graphql
query {
  recommendedListings {  # Returns 10 listings
    id
    title
  }
}
```

Without batching, the Listing node resolver is called 10 times, making 10 separate service calls.

### Solution: batchResolve

Override `batchResolve` instead of `resolve` to batch multiple requests:

```kotlin
@Resolver
class ListingNodeResolver @Inject constructor(
    private val listingService: ListingServiceClient
) : NodeResolvers.Listing() {

    override suspend fun batchResolve(contexts: List<Context>): List<FieldValue<Listing>> {
        // Batch all IDs into single request
        val listingIds = contexts.map { it.id.internalID }
        val responses = listingService.fetchBatch(listingIds)

        // Map responses back to contexts (same order!)
        return contexts.map { ctx ->
            val data = responses[ctx.id.internalID]
            if (data == null) {
                FieldValue.ofError(RuntimeException("Listing not found: ${ctx.id.internalID}"))
            } else {
                FieldValue.ofValue(
                    Listing.Builder(ctx)
                        .title(data.title)
                        .price(data.price)
                        .build()
                )
            }
        }
    }
}
```

### FieldValue Return Type

`batchResolve` returns `List<FieldValue<T>>` to handle per-item success/error:

```kotlin
// Success
FieldValue.ofValue(myObject)

// Error (null in response, error in errors array)
FieldValue.ofError(RuntimeException("Not found"))
```

### When to Use batchResolve

Use `batchResolve` when:
- Fetching data from external services
- Your service supports batch endpoints
- You're seeing N+1 query patterns

Don't use `batchResolve` when:
- Computing in-memory (no external dependencies)
- Your service doesn't support batch operations

## Field Batch Resolvers

Field resolvers can also batch. Useful when a field on many objects needs the same external data:

```graphql
type Listing implements Node {
  id: ID!
  title: String!
  hostName: String @resolver  # Needs to fetch from User service
}
```

```kotlin
@Resolver("fragment _ on Listing { hostId }")
class ListingHostNameResolver @Inject constructor(
    private val userService: UserServiceClient
) : ListingResolvers.HostName() {

    override suspend fun batchResolve(contexts: List<Context>): List<FieldValue<String?>> {
        // Collect all unique host IDs
        val hostIds = contexts.mapNotNull { it.objectValue.getHostId() }.distinct()

        // Single batch call
        val users = userService.fetchBatch(hostIds)

        // Map back to contexts
        return contexts.map { ctx ->
            val hostId = ctx.objectValue.getHostId()
            val user = hostId?.let { users[it] }
            FieldValue.ofValue(user?.let { "${it.firstName} ${it.lastName}" })
        }
    }
}
```

## Context for Queries

Query resolvers get a `Context` with:

| Property | Description |
|----------|-------------|
| `arguments` | The query arguments (typed based on schema) |
| `requestContext` | Application-specific context (auth, etc.) |
| `globalIDFor()` | Create GlobalIDs for response objects |
| `nodeFor()` | Create node references |
| `query()` | Execute subqueries |

## Code Examples

### Complete Query with Pagination

```graphql
extend type Query @scope(to: ["default"]) {
  products(
    category: String
    limit: Int = 20
    offset: Int = 0
  ): ProductConnection! @resolver
}

type ProductConnection @scope(to: ["default"]) {
  items: [Product!]!
  totalCount: Int!
  hasMore: Boolean!
}
```

```kotlin
@Resolver
class ProductsQueryResolver @Inject constructor(
    private val productService: ProductServiceClient
) : QueryResolvers.Products() {

    override suspend fun resolve(ctx: Context): ProductConnection {
        val category = ctx.arguments.category
        val limit = ctx.arguments.limit
        val offset = ctx.arguments.offset

        val result = productService.search(
            category = category,
            limit = limit + 1,  // Fetch one extra to check hasMore
            offset = offset
        )

        val hasMore = result.size > limit
        val items = result.take(limit)

        return ProductConnection.Builder(ctx)
            .items(items.map { data ->
                Product.Builder(ctx)
                    .id(ctx.globalIDFor(Product.Reflection, data.id))
                    .name(data.name)
                    .price(data.price)
                    .build()
            })
            .totalCount(productService.count(category))
            .hasMore(hasMore)
            .build()
    }
}
```

## Common Mistakes

### Missing @idOf on ID arguments

```graphql
# WRONG - id will be String, need manual Base64 decode
extend type Query {
  user(id: ID!): User @resolver
}

# CORRECT - id will be GlobalID<User>
extend type Query {
  user(id: ID! @idOf(type: "User")): User @resolver
}
```

### Wrong order in batchResolve response

```kotlin
// WRONG - order doesn't match input
override suspend fun batchResolve(contexts: List<Context>): List<FieldValue<User>> {
    val ids = contexts.map { it.id.internalID }
    val users = userService.fetchBatch(ids)  // Returns Map<String, UserData>

    return users.values.map { data ->  // WRONG! Order not preserved
        FieldValue.ofValue(buildUser(data))
    }
}

// CORRECT - preserve order
override suspend fun batchResolve(contexts: List<Context>): List<FieldValue<User>> {
    val ids = contexts.map { it.id.internalID }
    val users = userService.fetchBatch(ids)

    return contexts.map { ctx ->  // Map over contexts to preserve order
        val data = users[ctx.id.internalID]
        if (data == null) {
            FieldValue.ofError(RuntimeException("Not found"))
        } else {
            FieldValue.ofValue(buildUser(ctx, data))
        }
    }
}
```

### Not creating GlobalID for response objects

```kotlin
// WRONG - missing GlobalID
return User.Builder(ctx)
    .firstName(data.firstName)
    .build()

// CORRECT - include GlobalID for Node types
return User.Builder(ctx)
    .id(ctx.globalIDFor(User.Reflection, data.id))
    .firstName(data.firstName)
    .build()
```

## See Also

- [Entities](entities.md) - Node and field resolvers
- [Mutations](mutations.md) - Modifying data
- [GlobalIDs](../gotchas/global-ids.md) - GlobalID handling patterns
