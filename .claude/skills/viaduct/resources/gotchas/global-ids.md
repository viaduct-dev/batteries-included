# GlobalID Handling Guide

## Overview

This guide explains Viaduct's GlobalID system in depth. For quick reference on `@idOf` usage, see the callouts in [Queries](../core/queries.md) and [Mutations](../core/mutations.md).

## The Problem

Developers frequently make mistakes with Viaduct's GlobalID system, leading to:
- Manual Base64 encoding/decoding in resolvers (wrong approach)
- Missing `@idOf` directives causing type mismatches
- Confusion about when to use `.internalID` vs the full GlobalID

## Why It Happens

Viaduct's GlobalID system operates at different layers:

1. **GraphQL Layer**: GlobalIDs are base64-encoded strings (`VXNlcjoxMjM=`)
2. **Viaduct Layer**: `@idOf` directive deserializes to `GlobalID<T>` objects
3. **Policy Layer**: Runs BEFORE deserialization (gets raw strings)
4. **Resolver Layer**: Gets deserialized `GlobalID<T>` objects

The confusion arises because:
- Policy executors receive base64 strings (must decode manually)
- Resolvers receive `GlobalID<T>` objects (use `.internalID`)
- Missing `@idOf` directive causes resolvers to get strings too

## The Solution

### Rule 1: Always Use @idOf on Input ID Fields

```graphql
# CORRECT
input UpdateUserInput {
  id: ID! @idOf(type: "User")
  firstName: String
}

extend type Query {
  user(id: ID! @idOf(type: "User")): User @resolver
}

# WRONG - will generate String, not GlobalID
input UpdateUserInput {
  id: ID!  # Missing @idOf!
}
```

### Rule 2: In Resolvers, Use .internalID

```kotlin
// CORRECT - with @idOf in schema
@Resolver
class UpdateUserResolver : MutationResolvers.UpdateUser() {
    override suspend fun resolve(ctx: Context): User {
        // input.id is GlobalID<User>
        val userId: String = ctx.arguments.input.id.internalID
        val data = userService.update(userId, ...)
        // ...
    }
}

// WRONG - never do manual Base64 in resolvers
val decoded = String(Base64.getDecoder().decode(input.id))
val userId = decoded.substringAfter(":")
```

### Rule 3: In Policy Executors, Handle Both Types

Policy executors run BEFORE `@idOf` deserialization:

```kotlin
class MyPolicyExecutor : CheckerExecutor {
    override suspend fun execute(
        arguments: Map<String, Any?>,
        objectDataMap: Map<String, EngineObjectData>,
        context: EngineExecutionContext
    ): CheckerResult {
        val idArg = arguments["groupId"]

        // Must handle both cases!
        val internalId = when (idArg) {
            is GlobalID<*> -> idArg.internalID  // Unit tests may pass this
            is String -> {
                // Production: base64-encoded string
                val decoded = String(Base64.getDecoder().decode(idArg))
                decoded.substringAfter(":")
            }
            else -> error("Unexpected type: ${idArg?.javaClass}")
        }

        return checkAccess(internalId)
    }
}
```

### Rule 4: Create GlobalIDs for Response Objects

```kotlin
// CORRECT
return User.Builder(ctx)
    .id(ctx.globalIDFor(User.Reflection, data.id))  // Create GlobalID
    .firstName(data.firstName)
    .build()

// WRONG - passing internal ID directly
return User.Builder(ctx)
    .id(data.id)  // This is just a UUID string, not a GlobalID!
    .firstName(data.firstName)
    .build()
```

## Before/After Examples

### Input Type Definition

**Before (Wrong):**
```graphql
input UpdateChecklistItemInput {
  id: ID!
  title: String
}
```

**After (Correct):**
```graphql
input UpdateChecklistItemInput {
  id: ID! @idOf(type: "ChecklistItem")
  title: String
}
```

### Resolver Implementation

**Before (Wrong):**
```kotlin
override suspend fun resolve(ctx: Context): ChecklistItem {
    // Manual decoding - indicates missing @idOf
    val decoded = String(Base64.getDecoder().decode(input.id))
    val itemId = decoded.substringAfter(":")
    // ...
}
```

**After (Correct):**
```kotlin
override suspend fun resolve(ctx: Context): ChecklistItem {
    // Direct access with @idOf in schema
    val itemId = input.id.internalID
    // ...
}
```

### Query Argument

**Before (Wrong):**
```graphql
extend type Query {
  checklistItemsByGroup(groupId: ID!): [ChecklistItem!]! @resolver
}
```

**After (Correct):**
```graphql
extend type Query {
  checklistItemsByGroup(
    groupId: ID! @idOf(type: "CheckboxGroup")
  ): [ChecklistItem!]! @resolver
}
```

## Detection

Signs you have GlobalID problems:

1. **Manual Base64 code in resolvers** - You're calling `Base64.getDecoder().decode()`
2. **Type errors** - Expecting `GlobalID<T>` but getting `String`
3. **Runtime errors** - `IllegalArgumentException` when parsing IDs
4. **Generated code inspection** - Check if input fields are `String` vs `GlobalID<T>`

### Check Generated Code

Look at your generated input types:

```kotlin
// If you see String - @idOf is missing
class UpdateUserInput(
    val id: String,  // <-- Problem! Should be GlobalID
    val firstName: String?
)

// Correct with @idOf
class UpdateUserInput(
    val id: GlobalID<User>,  // <-- Correct!
    val firstName: String?
)
```

## Prevention

### Checklist for New Schema

- [ ] All `ID!` fields in input types have `@idOf(type: "TypeName")`
- [ ] All `ID!` arguments on queries/mutations have `@idOf(type: "TypeName")`
- [ ] Output types (implementing Node) do NOT need `@idOf` (automatic)
- [ ] After schema changes, regenerate code: `./gradlew generateViaduct...`

### Where @idOf is Required

| Location | @idOf Required? |
|----------|-----------------|
| Input type ID field | YES |
| Query argument ID | YES |
| Mutation argument ID | YES |
| Node type `id: ID!` field | NO (automatic) |
| Output type ID field | NO (String is fine) |

### Template for Input Types

```graphql
input [EntityName]Input @scope(to: ["default"]) {
  id: ID! @idOf(type: "[EntityName]")
  # other fields...
}
```

## Complete Example

### Schema

```graphql
type ChecklistItem implements Node @scope(to: ["default"]) {
  id: ID!  # No @idOf needed - Node types auto-handle this
  title: String!
  groupId: String  # Optional reference (as String for display)
}

input UpdateChecklistItemInput @scope(to: ["default"]) {
  id: ID! @idOf(type: "ChecklistItem")  # REQUIRED
  title: String
  completed: Boolean
}

input CreateChecklistItemInput @scope(to: ["default"]) {
  title: String!
  groupId: ID! @idOf(type: "CheckboxGroup")  # REQUIRED for ID refs
}

extend type Query @scope(to: ["default"]) {
  checklistItem(
    id: ID! @idOf(type: "ChecklistItem")  # REQUIRED
  ): ChecklistItem @resolver

  checklistItemsByGroup(
    groupId: ID! @idOf(type: "CheckboxGroup")  # REQUIRED
  ): [ChecklistItem!]! @resolver
}

extend type Mutation @scope(to: ["default"]) {
  updateChecklistItem(
    input: UpdateChecklistItemInput!
  ): ChecklistItem! @resolver

  createChecklistItem(
    input: CreateChecklistItemInput!
  ): ChecklistItem! @resolver
}
```

### Resolver

```kotlin
@Resolver
class UpdateChecklistItemResolver @Inject constructor(
    private val itemService: ChecklistItemService
) : MutationResolvers.UpdateChecklistItem() {

    override suspend fun resolve(ctx: Context): ChecklistItem {
        val input = ctx.arguments.input

        // Direct access thanks to @idOf
        val itemId: String = input.id.internalID

        val entity = itemService.update(
            id = itemId,
            title = input.title,
            completed = input.completed
        )

        return ChecklistItem.Builder(ctx)
            .id(ctx.globalIDFor(ChecklistItem.Reflection, entity.id))
            .title(entity.title)
            .completed(entity.completed)
            .groupId(entity.groupId)
            .build()
    }
}
```

## See Also

- [Entities](../core/entities.md) - Node resolver implementation
- [Mutations](../core/mutations.md) - Mutation patterns
- [Policy Checkers](policy-checkers.md) - Handling GlobalIDs in policies
