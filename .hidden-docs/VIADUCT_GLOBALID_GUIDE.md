# Viaduct GlobalID Usage Guide

This guide provides comprehensive instructions for working with GlobalIDs in Viaduct when creating new resolvers and integrating with existing databases.

## What is a GlobalID?

A GlobalID is Viaduct's type-safe wrapper around internal IDs. It's base64-encoded in the format `TypeName:internalId` and provides type safety across your GraphQL API. This is part of the Relay specification that Viaduct implements.

## Common Mistakes to Avoid

### ❌ WRONG - Manual Base64 Decoding in Resolvers

```kotlin
// DO NOT DO THIS IN RESOLVERS - This treats GlobalID as a String and manually decodes it
val decoded = String(Base64.getDecoder().decode(input.id))
val itemId = decoded.substringAfter(":")
```

**Why is this wrong in resolvers?** With `@idOf` directives, Viaduct automatically deserializes ID fields to GlobalID objects. You're bypassing the type system and doing manual string manipulation, which is error-prone.

### ✅ CORRECT - Use Viaduct's GlobalID API in Resolvers

```kotlin
// Viaduct automatically deserializes ID! fields to GlobalID objects (with @idOf directive)
val itemId = input.id.internalID  // Direct access to internal ID as String
```

**Why is this correct?** With `@idOf` directives, Viaduct handles the deserialization for you. The `input.id` is already a `GlobalID<T>` object with an `.internalID` property.

### ⚠️ IMPORTANT - Policy Executors Are Different

**Policy executors run at the engine level, BEFORE argument deserialization** and DO need Base64 decoding:

```kotlin
// In a CheckerExecutor (policy executor), you MUST handle both:
val internalGroupId = when (groupIdArg) {
    is GlobalID<*> -> groupIdArg.internalID
    is String -> {
        // Policy executors receive base64-encoded strings from GraphQL
        // Format: base64("TypeName:uuid")
        val decoded = String(Base64.getDecoder().decode(groupIdArg))
        decoded.substringAfter(":")  // Extract just the UUID part
    }
    else -> error("Unexpected type")
}
```

**Why is this necessary?**

Policy executors run **before** the `@idOf` deserialization happens:

1. **GraphQL Query** → Client sends base64-encoded GlobalID string
2. **Policy Executor** ← Receives raw base64 string (must decode manually)
3. **Argument Deserialization** → `@idOf` directive converts to GlobalID objects
4. **Resolver** ← Receives deserialized GlobalID object (use `.internalID`)

This is an architectural constraint of Viaduct's execution pipeline. Policy executors cannot avoid Base64 decoding because they execute before the deserialization layer.

**Key Insight:** Manual Base64 decoding in policy executors is **unavoidable** due to Viaduct's architecture:

- **Viaduct's GlobalIDCodec is inaccessible** - The codec lives in `InternalContext`, which policy executors cannot access
- **EngineExecutionContext.requestContext is opaque** - It's typed as `Any?` and contains application-specific data
- **CheckerExecutorFactory has no codec access** - The factory creates executors before execution context is available
- **Policy executors run before deserialization** - They fundamentally operate at a lower layer than the `@idOf` directive

Even with proper `@idOf` directives, policy executors will receive base64-encoded strings in production and must decode them manually. Handle GlobalID objects as well for unit test compatibility.

## GraphQL Schema Declarations

### For Types Implementing Node

```graphql
type ChecklistItem implements Node {
  id: ID!  # This will be a GlobalID<ChecklistItem> in resolvers
  title: String!
  groupId: ID  # Optional GlobalID reference - GlobalID<CheckboxGroup>?
}
```

### For Input Types

```graphql
input UpdateChecklistItemInput {
  id: ID!  # This will be deserialized as GlobalID<ChecklistItem> in Kotlin
  title: String
}

input CreateChecklistItemInput {
  title: String!
  groupId: ID!  # GlobalID<CheckboxGroup> in Kotlin
}
```

### For Query/Mutation Arguments

```graphql
type Query {
  checklistItemsByGroup(groupId: ID!): [ChecklistItem!]!
  # groupId will be GlobalID<CheckboxGroup> in resolver
}
```

### ⚠️ CRITICAL: Always Use @idOf Directive in Input Types

**IMPORTANT:** ID fields in input types and query arguments MUST use the `@idOf` directive to generate proper GlobalID types. Without this directive, Viaduct will generate String fields instead of GlobalID objects, forcing you to manually decode Base64 strings.

#### ✅ CORRECT - With @idOf Directive

```graphql
input UpdateChecklistItemInput {
  id: ID! @idOf(type: "ChecklistItem")
  title: String
}

input CreateChecklistItemInput {
  title: String!
  groupId: ID! @idOf(type: "CheckboxGroup")
}

input AddGroupMemberInput {
  groupId: ID! @idOf(type: "CheckboxGroup")
  userId: String!
}

type Query {
  checkboxGroup(id: ID! @idOf(type: "CheckboxGroup")): CheckboxGroup
  checklistItemsByGroup(groupId: ID! @idOf(type: "CheckboxGroup")): [ChecklistItem!]!
}
```

**Result:** Viaduct generates:
```kotlin
// input.id is GlobalID<ChecklistItem>
val itemId = input.id.internalID  // ✅ Type-safe access
```

#### ❌ WRONG - Without @idOf Directive

```graphql
input UpdateChecklistItemInput {
  id: ID!  # Missing @idOf!
  title: String
}
```

**Result:** Viaduct generates:
```kotlin
// input.id is String (base64-encoded)
val decoded = String(Base64.getDecoder().decode(input.id))  // ❌ Manual decoding required!
val itemId = decoded.substringAfter(":")
```

#### Rule of Thumb

Add `@idOf(type: "TypeName")` to **every** ID field in:
- ✅ Input types
- ✅ Query/Mutation arguments
- ❌ NOT needed on output types (types implementing Node automatically get proper GlobalID handling)

```graphql
# Output type - @idOf NOT needed (automatic)
type ChecklistItem implements Node {
  id: ID!  # No @idOf needed here
  groupId: ID  # No @idOf needed here
}

# Input type - @idOf REQUIRED
input UpdateChecklistItemInput {
  id: ID! @idOf(type: "ChecklistItem")  # @idOf REQUIRED
}
```

## Resolver Implementation Patterns

### Pattern 1: Extracting Internal ID from Mutation Input

```kotlin
@Resolver
class UpdateChecklistItemResolver(
    private val groupService: GroupService
) : MutationResolvers.UpdateChecklistItem() {
    override suspend fun resolve(ctx: Context): ChecklistItem {
        val input = ctx.arguments.input

        // ✅ CORRECT: Access internalID property
        val itemId: String = input.id.internalID

        val client = groupService.supabaseService.getAuthenticatedClient(ctx.requestContext)

        // Now use itemId (a String/UUID) with your database client
        val itemEntity = client.updateChecklistItem(
            id = itemId,
            completed = input.completed,
            title = input.title
        )

        // When building the response, create GlobalID from internal ID
        return ChecklistItem.Builder(ctx)
            .id(ctx.globalIDFor(ChecklistItem.Reflection, itemEntity.id))
            .title(itemEntity.title)
            .completed(itemEntity.completed)
            .userId(itemEntity.user_id)
            .groupId(itemEntity.group_id)
            .createdAt(itemEntity.created_at)
            .updatedAt(itemEntity.updated_at)
            .build()
    }
}
```

### Pattern 2: Query Parameter GlobalID

```kotlin
@Resolver
class ChecklistItemsByGroupQueryResolver(
    private val groupService: GroupService
) : QueryResolvers.ChecklistItemsByGroup() {
    override suspend fun resolve(ctx: Context): List<ChecklistItem> {
        // ✅ CORRECT: Arguments with ID! type are already GlobalID objects
        val groupId: String = ctx.arguments.groupId.internalID

        val client = groupService.supabaseService.getAuthenticatedClient(ctx.requestContext)
        val itemEntities = client.getChecklistItemsByGroup(groupId)

        return itemEntities.map { entity ->
            ChecklistItem.Builder(ctx)
                .id(ctx.globalIDFor(ChecklistItem.Reflection, entity.id))
                .title(entity.title)
                .completed(entity.completed)
                .userId(entity.user_id)
                .groupId(entity.group_id)
                .createdAt(entity.created_at)
                .updatedAt(entity.updated_at)
                .build()
        }
    }
}
```

### Pattern 3: Creating GlobalIDs for Response Objects

```kotlin
// ✅ CORRECT: When building response objects that implement Node
return ChecklistItem.Builder(ctx)
    .id(ctx.globalIDFor(ChecklistItem.Reflection, entity.id))  // Create GlobalID
    .title(entity.title)
    .build()

// ❌ WRONG: Don't pass internal ID directly as ID field
// .id(entity.id)  // This is just the internal UUID string, not a GlobalID!
```

### Pattern 4: Optional GlobalID Fields

```kotlin
// When a field can be null (groupId: ID in schema)
return ChecklistItem.Builder(ctx)
    .id(ctx.globalIDFor(ChecklistItem.Reflection, entity.id))
    .groupId(entity.group_id)  // Can be null - Viaduct handles String? correctly
    .build()
```

## Type Safety with GlobalID<T>

Viaduct's GlobalID is generic and type-safe:

```kotlin
// Type parameters prevent mixing up IDs from different types
val itemId: GlobalID<ChecklistItem> = input.id
val groupId: GlobalID<CheckboxGroup> = input.groupId

// Extract the raw internal ID (String)
val internalItemId: String = itemId.internalID
val internalGroupId: String = groupId.internalID

// Type safety - this would be a compilation error:
// itemId == groupId  // ❌ Error: cannot compare GlobalID<ChecklistItem> with GlobalID<CheckboxGroup>
```

## Working with Existing Database Schemas

When adding Viaduct to an existing database application where you already know the schema:

### Step 1: Define GraphQL Schema with ID! Types

```graphql
type ChecklistItem implements Node {
  id: ID!  # Use ID! not String!
  title: String!
}

input UpdateChecklistItemInput {
  id: ID!  # Use ID! not String!
  title: String
}
```

### Step 2: Keep Database Layer Using Internal IDs

Your database client can continue using UUIDs/internal IDs:

```kotlin
// Supabase/database client interface
interface SupabaseClient {
    suspend fun updateChecklistItem(id: String, title: String?): ChecklistItemEntity
    suspend fun getChecklistItemById(id: String): ChecklistItemEntity?
}
```

### Step 3: Convert at GraphQL Boundary (Resolver)

```kotlin
@Resolver
class UpdateChecklistItemResolver : MutationResolvers.UpdateChecklistItem() {
    override suspend fun resolve(ctx: Context): ChecklistItem {
        // STEP A: Convert GlobalID → String for database layer
        val internalId: String = input.id.internalID

        // STEP B: Use internal ID with database client
        val entity = databaseClient.updateChecklistItem(
            id = internalId,
            title = input.title
        )

        // STEP C: Convert String → GlobalID for GraphQL layer
        return ChecklistItem.Builder(ctx)
            .id(ctx.globalIDFor(ChecklistItem.Reflection, entity.id))
            .title(entity.title)
            .build()
    }
}
```

### Data Flow Diagram

```
GraphQL Request → Resolver → Database → Resolver → GraphQL Response
   GlobalID    →  .internalID  →  UUID  → globalIDFor() → GlobalID
```

## Complete Working Example

Here's a full example showing all patterns together:

```kotlin
@Resolver
class ChecklistItemOperationsResolver(
    private val groupService: GroupService
) : MutationResolvers.UpdateChecklistItem() {

    override suspend fun resolve(ctx: Context): ChecklistItem {
        val input = ctx.arguments.input

        // 1. Extract internal IDs from GlobalIDs
        val itemId = input.id.internalID
        val groupId = input.groupId?.internalID  // Optional field

        // 2. Get authenticated client
        val client = groupService.supabaseService.getAuthenticatedClient(ctx.requestContext)

        // 3. Perform database operation with internal IDs
        val entity = client.updateChecklistItem(
            id = itemId,
            title = input.title,
            completed = input.completed
        )

        // 4. Build response object with GlobalIDs
        return ChecklistItem.Builder(ctx)
            .id(ctx.globalIDFor(ChecklistItem.Reflection, entity.id))
            .title(entity.title)
            .completed(entity.completed)
            .userId(entity.user_id)
            .groupId(entity.group_id)  // String? from database
            .createdAt(entity.created_at)
            .updatedAt(entity.updated_at)
            .build()
    }
}
```

## Best Practices Checklist

When creating a new resolver, verify:

- [ ] GraphQL schema uses `ID!` (not `String!`) for all ID fields
- [ ] **All input types and query arguments with ID fields use `@idOf(type: "TypeName")` directive**
- [ ] No manual Base64 encoding/decoding in resolver code
- [ ] Extract internal IDs using `.internalID` property
- [ ] Create GlobalIDs in responses using `ctx.globalIDFor(Type.Reflection, internalId)`
- [ ] Database layer operates on String/UUID internal IDs
- [ ] Type conversions happen at the resolver boundary only
- [ ] All types implementing `Node` return `ID!` from their `id` field

## Troubleshooting

### My input.id is a String, not a GlobalID object

**Symptoms:**
- Need to manually decode Base64 strings in resolvers
- Input ID fields are `String` type in generated code
- Have to use `Base64.getDecoder().decode(input.id)`

**Cause:** Missing `@idOf` directive in your GraphQL schema

**Solution:** Add `@idOf(type: "TypeName")` to all ID fields in input types and query arguments:

```graphql
# Before (generates String)
input UpdateChecklistItemInput {
  id: ID!
}

# After (generates GlobalID<ChecklistItem>)
input UpdateChecklistItemInput {
  id: ID! @idOf(type: "ChecklistItem")
}
```

Then regenerate Viaduct types and update resolvers to use `.internalID`.

### Error: "Cannot convert String to GlobalID"

**Cause:** You're trying to pass a String directly where GlobalID is expected.

**Solution:** Use `ctx.globalIDFor(Type.Reflection, stringId)`

### Error: "Expected GlobalID but got String"

**Cause:** Your schema declares `String!` instead of `ID!`

**Solution:** Change schema from `String!` to `ID!`

### My resolver has Base64.decode() calls

**Cause:** Either:
1. Missing `@idOf` directive in schema (most common)
2. Using legacy pattern from before understanding Viaduct's type system

**Solution:**
1. Add `@idOf` directives to schema input types
2. Regenerate Viaduct types with `./gradlew generateViaduct...`
3. Remove manual decoding and use `.internalID` property instead
