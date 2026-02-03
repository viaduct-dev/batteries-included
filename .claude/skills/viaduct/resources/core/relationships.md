# Entity Relationships

## Overview

Viaduct supports various relationship patterns between entities using node references and subqueries. This enables efficient resolution of connected data without N+1 query problems.

## Navigation

- Prerequisites: [Entities](entities.md), [Queries](queries.md)
- Related: [GlobalIDs](../gotchas/global-ids.md)
- Next Steps: [Policy Checkers](../gotchas/policy-checkers.md)

## Relationship Types

### One-to-One

```graphql
type User implements Node {
  id: ID!
  name: String!
  profile: UserProfile @resolver
}

type UserProfile implements Node {
  id: ID!
  bio: String
  avatarUrl: String
}
```

### One-to-Many

```graphql
type User implements Node {
  id: ID!
  name: String!
  posts: [Post!]! @resolver
}

type Post implements Node {
  id: ID!
  title: String!
  author: User @resolver
}
```

### Many-to-Many

```graphql
type User implements Node {
  id: ID!
  name: String!
  groups: [Group!]! @resolver
}

type Group implements Node {
  id: ID!
  name: String!
  members: [User!]! @resolver
}
```

## Node References

**⚠️ WARNING: If modifying an existing type to add `implements Node`, you MUST update ALL existing resolvers that build that type to use `ctx.globalIDFor()` for the id field. Search for `TypeName.Builder` to find all places that need updating.**

When a field returns a Node type, you can return a **node reference** - Viaduct will automatically call the appropriate Node Resolver:

```kotlin
@Resolver("fragment _ on Post { authorId }")
class PostAuthorResolver : PostResolvers.Author() {

    override suspend fun resolve(ctx: Context): User {
        val authorId = ctx.objectValue.getAuthorId()
            ?: return null

        // Create a node reference - Viaduct handles the rest
        return ctx.nodeFor(
            ctx.globalIDFor(User.Reflection, authorId)
        )
    }
}
```

**Important:** For `ctx.nodeFor()` to work, the target type must:

1. **Implement `Node`** - Add `implements Node` to the type
2. **Have `@resolver`** - The type must have `@resolver` to generate `NodeResolvers.TypeName` base class
3. **Have a NodeResolver** - You must create a resolver extending `NodeResolvers.TypeName()`

**If modifying an existing type to support nodeFor:**

1. Add `implements Node @resolver` to the type definition
2. Create a NodeResolver class (e.g., `UserNodeResolver extends NodeResolvers.User()`)
3. **CRITICAL**: Update **ALL** existing resolvers that build this type to use `ctx.globalIDFor(TypeName.Reflection, id)` for the id field

**Example of required changes when adding `implements Node` to an existing type:**

```kotlin
// BEFORE: When User does NOT implement Node
return User.Builder(ctx)
    .id(userId)  // Accepts String
    .email(email)
    .build()

// AFTER: When User implements Node
return User.Builder(ctx)
    .id(ctx.globalIDFor(User.Reflection, userId))  // Must be GlobalID<User>
    .email(email)
    .build()
```

**Find and update ALL resolvers** that create this type (search for `TypeName.Builder`).

### Benefits of Node References

1. **Batching**: Multiple node references are batched together
2. **Consistency**: Node Resolver logic is reused
3. **Simplicity**: No need to duplicate data fetching

### Creating Node References

```kotlin
// From internal ID
val userRef = ctx.nodeFor(ctx.globalIDFor(User.Reflection, userId))

// From existing GlobalID
val userRef = ctx.nodeFor(globalId)

// For nullable fields
val userRef = userId?.let { ctx.nodeFor(ctx.globalIDFor(User.Reflection, it)) }
```

## Subqueries

For more complex data requirements, use subqueries to execute GraphQL queries within resolvers:

```kotlin
@Resolver("fragment _ on Order { customerId }")
class OrderCustomerDetailsResolver @Inject constructor(
    private val customerService: CustomerServiceClient
) : OrderResolvers.CustomerDetails() {

    override suspend fun resolve(ctx: Context): CustomerDetails? {
        val customerId = ctx.objectValue.getCustomerId()
            ?: return null

        // Execute a subquery for customer data
        val result = ctx.query(
            """
            query(${"$"}id: ID!) {
                customer(id: ${"$"}id) {
                    name
                    email
                    memberSince
                }
            }
            """,
            mapOf("id" to ctx.globalIDFor(Customer.Reflection, customerId).toString())
        )

        return result["customer"]?.let { customer ->
            CustomerDetails.Builder(ctx)
                .name(customer["name"] as String)
                .email(customer["email"] as String)
                .memberSince(customer["memberSince"] as String)
                .build()
        }
    }
}
```

## Complete Example: Adding a createdBy Relationship

This is the most common relationship pattern - adding a reference to another entity.

### Step 1: Update Schema (ALWAYS FIRST)

```graphql
# Tag.graphqls
type Tag implements Node @scope(to: ["default"]) {
  id: ID!
  name: String!
  color: String

  # Store the raw ID for reference
  createdById: String!

  # Relationship field - resolved separately
  createdBy: User @resolver
}
```

**Important:**
- `createdById: String!` stores the raw UUID (for display/filtering)
- `createdBy: User @resolver` is the relationship field that returns the User object
- The User type MUST implement `Node` for `ctx.nodeFor()` to work

### Step 2: Implement Field Resolver

```kotlin
@Resolver("fragment _ on Tag { createdById }")
class TagCreatedByResolver : TagResolvers.CreatedBy() {

    override suspend fun resolve(ctx: Context): User? {
        val createdById = ctx.objectValue.getCreatedById()
            ?: return null

        // Return node reference - Viaduct calls UserNodeResolver
        return ctx.nodeFor(
            ctx.globalIDFor(User.Reflection, createdById)
        )
    }
}
```

### Step 3: Update Node Resolver (if needed)

The Tag Node Resolver should set `createdById` but NOT `createdBy`:

```kotlin
@Resolver
class TagNodeResolver @Inject constructor(
    private val tagService: TagServiceClient
) : NodeResolvers.Tag() {

    override suspend fun resolve(ctx: Context): Tag {
        val data = tagService.fetch(ctx.id.internalID)

        return Tag.Builder(ctx)
            .name(data.name)
            .color(data.color)
            .createdById(data.createdById)  // Set the raw ID
            // DO NOT set createdBy - it has @resolver
            .build()
    }
}
```

## Code Examples

### One-to-Many with Batching

```graphql
type User implements Node {
  id: ID!
  name: String!
  posts: [Post!]! @resolver
}
```

```kotlin
@Resolver("fragment _ on User { }")
class UserPostsResolver @Inject constructor(
    private val postService: PostServiceClient
) : UserResolvers.Posts() {

    override suspend fun batchResolve(contexts: List<Context>): List<FieldValue<List<Post>>> {
        // Batch fetch all posts for all users
        val userIds = contexts.map { ctx ->
            ctx.objectValue.getId().internalID
        }

        val postsByUser = postService.fetchByAuthors(userIds)

        return contexts.map { ctx ->
            val userId = ctx.objectValue.getId().internalID
            val posts = postsByUser[userId] ?: emptyList()

            FieldValue.ofValue(
                posts.map { data ->
                    Post.Builder(ctx)
                        .id(ctx.globalIDFor(Post.Reflection, data.id))
                        .title(data.title)
                        .build()
                }
            )
        }
    }
}
```

### Back-reference (Post -> Author)

```kotlin
@Resolver("fragment _ on Post { authorId }")
class PostAuthorResolver : PostResolvers.Author() {

    override suspend fun resolve(ctx: Context): User? {
        val authorId = ctx.objectValue.getAuthorId()
            ?: return null

        // Node reference - batched with other author lookups
        return ctx.nodeFor(ctx.globalIDFor(User.Reflection, authorId))
    }
}
```

### Many-to-Many (User Groups)

```graphql
# Schema
type User implements Node {
  id: ID!
  groups: [Group!]! @resolver
}

type Group implements Node {
  id: ID!
  members: [User!]! @resolver
}

# Hidden join type (not exposed in API)
# UserGroup table: user_id, group_id
```

```kotlin
@Resolver("fragment _ on User { }")
class UserGroupsResolver @Inject constructor(
    private val membershipService: MembershipServiceClient
) : UserResolvers.Groups() {

    override suspend fun batchResolve(contexts: List<Context>): List<FieldValue<List<Group>>> {
        val userIds = contexts.map { it.objectValue.getId().internalID }

        // Fetch group IDs for all users
        val groupIdsByUser = membershipService.getGroupIdsForUsers(userIds)

        return contexts.map { ctx ->
            val userId = ctx.objectValue.getId().internalID
            val groupIds = groupIdsByUser[userId] ?: emptyList()

            // Return node references - Group Node Resolver handles the rest
            FieldValue.ofValue(
                groupIds.map { groupId ->
                    ctx.nodeFor(ctx.globalIDFor(Group.Reflection, groupId))
                }
            )
        }
    }
}
```

### Nested Relationships

```graphql
type Organization implements Node {
  id: ID!
  name: String!
  teams: [Team!]! @resolver
}

type Team implements Node {
  id: ID!
  name: String!
  organization: Organization @resolver
  members: [User!]! @resolver
}
```

Query:
```graphql
query {
  organization(id: "...") {
    name
    teams {
      name
      members {
        name
        email
      }
    }
  }
}
```

Each level uses its own resolver:
1. `OrganizationNodeResolver` fetches organization
2. `OrganizationTeamsResolver` fetches teams (batched)
3. `TeamMembersResolver` fetches members (batched)

## Common Mistakes

### Fetching related data in parent resolver

```kotlin
// WRONG - don't fetch posts in User Node Resolver
@Resolver
class UserNodeResolver : NodeResolvers.User() {
    override suspend fun resolve(ctx: Context): User {
        val user = userService.fetch(ctx.id.internalID)
        val posts = postService.fetchByAuthor(ctx.id.internalID)  // Don't do this!

        return User.Builder(ctx)
            .name(user.name)
            .posts(posts.map { ... })  // Wrong!
            .build()
    }
}

// CORRECT - let the field resolver handle it
@Resolver
class UserNodeResolver : NodeResolvers.User() {
    override suspend fun resolve(ctx: Context): User {
        val user = userService.fetch(ctx.id.internalID)

        return User.Builder(ctx)
            .name(user.name)
            .build()
        // posts field has @resolver, let it handle fetching
    }
}
```

### N+1 in relationship resolvers

```kotlin
// WRONG - N+1 queries
@Resolver("fragment _ on Post { authorId }")
class PostAuthorResolver @Inject constructor(
    private val userService: UserServiceClient
) : PostResolvers.Author() {

    override suspend fun resolve(ctx: Context): User? {
        val authorId = ctx.objectValue.getAuthorId() ?: return null
        val user = userService.fetch(authorId)  // Called N times!
        return User.Builder(ctx)...
    }
}

// CORRECT - use node reference (batched via Node Resolver)
@Resolver("fragment _ on Post { authorId }")
class PostAuthorResolver : PostResolvers.Author() {

    override suspend fun resolve(ctx: Context): User? {
        val authorId = ctx.objectValue.getAuthorId() ?: return null
        return ctx.nodeFor(ctx.globalIDFor(User.Reflection, authorId))
    }
}
```

### Circular reference infinite loops

Viaduct handles circular references automatically through the Node system:

```graphql
type User { posts: [Post!]! @resolver }
type Post { author: User @resolver }
```

Query like this works fine:
```graphql
query {
  user(id: "...") {
    posts {
      author {
        posts {  # Viaduct stops here - same user node
          title
        }
      }
    }
  }
}
```

## See Also

- [Entities](entities.md) - Node and field resolvers
- [Queries](queries.md) - Batch resolution patterns
- [GlobalIDs](../gotchas/global-ids.md) - Creating GlobalIDs for relationships
