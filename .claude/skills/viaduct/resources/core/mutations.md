# Mutation Implementation

## Overview

Mutations modify data in your GraphQL API. They follow similar patterns to query resolvers but can execute submutations and typically return the modified entity.

## Navigation

- Prerequisites: [Entities](entities.md), [Queries](queries.md)
- Related: [GlobalIDs](../gotchas/global-ids.md)
- Next Steps: [Relationships](relationships.md), [Policy Checkers](../gotchas/policy-checkers.md)

## Mutation Definition

Define mutations by extending the `Mutation` type:

```graphql
extend type Mutation @scope(to: ["default"]) {
  # Create
  createUser(input: CreateUserInput!): User! @resolver

  # Update
  updateUser(input: UpdateUserInput!): User! @resolver

  # Delete
  deleteUser(id: ID! @idOf(type: "User")): Boolean! @resolver
}

input CreateUserInput @scope(to: ["default"]) {
  firstName: String!
  lastName: String!
  email: String!
}

input UpdateUserInput @scope(to: ["default"]) {
  id: ID! @idOf(type: "User")  # CRITICAL: Use @idOf!
  firstName: String
  lastName: String
  email: String
}
```

**Critical**: Always use `@idOf` on ID fields in input types. See [GlobalID Guide](../gotchas/global-ids.md) for complete patterns.

## Mutation Resolver Implementation

### Create Mutation

```kotlin
@Resolver
class CreateUserResolver @Inject constructor(
    private val userService: UserServiceClient
) : MutationResolvers.CreateUser() {

    override suspend fun resolve(ctx: Context): User {
        val input = ctx.arguments.input

        val data = userService.create(
            firstName = input.firstName,
            lastName = input.lastName,
            email = input.email
        )

        return User.Builder(ctx)
            .id(ctx.globalIDFor(User.Reflection, data.id))
            .firstName(data.firstName)
            .lastName(data.lastName)
            .email(data.email)
            .build()
    }
}
```

### Update Mutation

```kotlin
@Resolver
class UpdateUserResolver @Inject constructor(
    private val userService: UserServiceClient
) : MutationResolvers.UpdateUser() {

    override suspend fun resolve(ctx: Context): User {
        val input = ctx.arguments.input

        // input.id is GlobalID<User> thanks to @idOf
        val userId = input.id.internalID

        val data = userService.update(
            id = userId,
            firstName = input.firstName,
            lastName = input.lastName,
            email = input.email
        )

        return User.Builder(ctx)
            .id(ctx.globalIDFor(User.Reflection, data.id))
            .firstName(data.firstName)
            .lastName(data.lastName)
            .email(data.email)
            .build()
    }
}
```

### Delete Mutation

```kotlin
@Resolver
class DeleteUserResolver @Inject constructor(
    private val userService: UserServiceClient
) : MutationResolvers.DeleteUser() {

    override suspend fun resolve(ctx: Context): Boolean {
        // ctx.arguments.id is GlobalID<User>
        val userId = ctx.arguments.id.internalID

        return userService.delete(userId)
    }
}
```

## Mutation Context

Mutation resolvers get a `Context` implementing `MutationFieldExecutionContext`:

| Property | Description |
|----------|-------------|
| `arguments` | The mutation arguments |
| `arguments.input` | Input object (for input-based mutations) |
| `requestContext` | Application-specific context (auth, etc.) |
| `globalIDFor()` | Create GlobalIDs for response objects |
| `nodeFor()` | Create node references |
| `mutation()` | Execute submutations |
| `query()` | Execute subqueries |

## Submutations

Mutations can call other mutations using `ctx.mutation()`:

```graphql
extend type Mutation {
  createTeam(input: CreateTeamInput!): Team! @resolver
  addTeamMember(input: AddTeamMemberInput!): TeamMember! @resolver
  createTeamWithMembers(input: CreateTeamWithMembersInput!): Team! @resolver
}
```

```kotlin
@Resolver
class CreateTeamWithMembersResolver @Inject constructor(
    private val teamService: TeamServiceClient
) : MutationResolvers.CreateTeamWithMembers() {

    override suspend fun resolve(ctx: Context): Team {
        val input = ctx.arguments.input

        // 1. Create the team
        val team = teamService.create(name = input.name)

        // 2. Add members using submutation
        input.memberIds.forEach { memberId ->
            ctx.mutation(
                "addTeamMember",
                mapOf(
                    "input" to mapOf(
                        "teamId" to ctx.globalIDFor(Team.Reflection, team.id).toString(),
                        "userId" to memberId.toString()
                    )
                )
            )
        }

        return Team.Builder(ctx)
            .id(ctx.globalIDFor(Team.Reflection, team.id))
            .name(team.name)
            .build()
    }
}
```

## Returning Node References

When a mutation returns a node type, you can return a node reference instead of building the full object:

```kotlin
@Resolver
class PublishListingResolver @Inject constructor(
    private val listingService: ListingServiceClient
) : MutationResolvers.PublishListing() {

    override suspend fun resolve(ctx: Context): Listing {
        val listingId = ctx.arguments.id.internalID

        listingService.publish(listingId)

        // Return a node reference - the Node Resolver will fetch full data
        return ctx.nodeFor(ctx.arguments.id)
    }
}
```

This is useful when:
- The mutation doesn't return the full entity data
- You want the Node Resolver to handle data fetching consistently
- The entity might have fields from multiple services

## Code Examples

### Complete CRUD Example

```graphql
type Product implements Node @scope(to: ["default"]) {
  id: ID!
  name: String!
  price: Float!
  active: Boolean!
}

input CreateProductInput @scope(to: ["default"]) {
  name: String!
  price: Float!
}

input UpdateProductInput @scope(to: ["default"]) {
  id: ID! @idOf(type: "Product")
  name: String
  price: Float
  active: Boolean
}

extend type Mutation @scope(to: ["default"]) {
  createProduct(input: CreateProductInput!): Product! @resolver
  updateProduct(input: UpdateProductInput!): Product! @resolver
  deleteProduct(id: ID! @idOf(type: "Product")): Boolean! @resolver
}
```

### Input Validation

```kotlin
@Resolver
class CreateProductResolver @Inject constructor(
    private val productService: ProductServiceClient
) : MutationResolvers.CreateProduct() {

    override suspend fun resolve(ctx: Context): Product {
        val input = ctx.arguments.input

        // Validate input
        require(input.name.isNotBlank()) { "Product name cannot be blank" }
        require(input.price > 0) { "Product price must be positive" }

        val data = productService.create(
            name = input.name.trim(),
            price = input.price
        )

        return Product.Builder(ctx)
            .id(ctx.globalIDFor(Product.Reflection, data.id))
            .name(data.name)
            .price(data.price)
            .active(data.active)
            .build()
    }
}
```

### Mutation with Side Effects

```kotlin
@Resolver
class CreateOrderResolver @Inject constructor(
    private val orderService: OrderServiceClient,
    private val notificationService: NotificationServiceClient,
    private val analyticsService: AnalyticsServiceClient
) : MutationResolvers.CreateOrder() {

    override suspend fun resolve(ctx: Context): Order {
        val input = ctx.arguments.input
        val userId = ctx.requestContext.userId

        // Create the order
        val order = orderService.create(
            userId = userId,
            items = input.items.map { it.productId.internalID to it.quantity }
        )

        // Side effects (consider using async/events in production)
        try {
            notificationService.sendOrderConfirmation(order.id, userId)
            analyticsService.trackOrderCreated(order.id)
        } catch (e: Exception) {
            // Log but don't fail the mutation
            logger.error("Side effect failed", e)
        }

        return Order.Builder(ctx)
            .id(ctx.globalIDFor(Order.Reflection, order.id))
            .status(order.status)
            .total(order.total)
            .build()
    }
}
```

## Common Mistakes

### Missing @idOf on input ID fields

```graphql
# WRONG - id will be String
input UpdateUserInput {
  id: ID!
  firstName: String
}

# CORRECT
input UpdateUserInput {
  id: ID! @idOf(type: "User")
  firstName: String
}
```

### Manual Base64 decoding in mutation resolvers

```kotlin
// WRONG - never manually decode in resolvers
val userId = String(Base64.getDecoder().decode(input.id)).substringAfter(":")

// CORRECT - use @idOf in schema, then:
val userId = input.id.internalID
```

### Forgetting to return GlobalID in response

```kotlin
// WRONG - missing id field
return User.Builder(ctx)
    .firstName(data.firstName)
    .build()

// CORRECT
return User.Builder(ctx)
    .id(ctx.globalIDFor(User.Reflection, data.id))
    .firstName(data.firstName)
    .build()
```

### Using query() in mutations for mutation calls

```kotlin
// WRONG - use mutation() for mutations
ctx.query("createUser", ...)

// CORRECT
ctx.mutation("createUser", ...)
```

## See Also

- [Entities](entities.md) - Node and field resolvers
- [GlobalIDs](../gotchas/global-ids.md) - GlobalID handling in mutations
- [Policy Checkers](../gotchas/policy-checkers.md) - Authorization for mutations
