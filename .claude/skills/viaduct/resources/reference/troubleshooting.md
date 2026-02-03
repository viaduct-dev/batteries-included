# Troubleshooting Guide

## Overview

Common errors and their solutions when developing Viaduct applications. Each section includes the error message, cause, and fix.

## Navigation

- Prerequisites: [Main Guide](../../viaduct.md)
- Related: [GlobalIDs](../gotchas/global-ids.md), [Policy Checkers](../gotchas/policy-checkers.md)

## Build Errors

### "Cannot find class NodeResolvers.X"

**Error:**
```
Unresolved reference: NodeResolvers
```

**Cause:** Viaduct code generation hasn't run or failed.

**Fix:**
```bash
./gradlew generateViaductTypes
# or
./gradlew clean build
```

### "Type X does not implement Node"

**Error:**
```
Type 'Product' must implement interface 'Node' to be used as a node type
```

**Cause:** Missing `implements Node` in schema.

**Fix:**
```graphql
# Wrong
type Product {
  id: ID!
}

# Correct
type Product implements Node {
  id: ID!
}
```

### "@idOf type not found"

**Error:**
```
Type 'Foo' referenced in @idOf directive does not exist
```

**Cause:** Typo in type name or type not defined.

**Fix:**
```graphql
# Check exact type name
input UpdateInput {
  id: ID! @idOf(type: "Product")  # Must match exactly
}
```

## Runtime Errors

### "UnsetSelectionException"

**Error:**
```
UnsetSelectionException: Field 'firstName' was accessed but not selected
```

**Cause:** Accessing field not in required selection set.

**Fix:**
```kotlin
// Wrong - missing lastName in fragment
@Resolver("fragment _ on User { firstName }")
class DisplayNameResolver : UserResolvers.DisplayName() {
    override suspend fun resolve(ctx: Context): String {
        val ln = ctx.objectValue.getLastName()  // Error!
    }
}

// Correct
@Resolver("fragment _ on User { firstName lastName }")
class DisplayNameResolver : UserResolvers.DisplayName() {
    override suspend fun resolve(ctx: Context): String {
        val ln = ctx.objectValue.getLastName()  // Works
    }
}
```

### "Expected GlobalID but got String"

**Error:**
```
ClassCastException: java.lang.String cannot be cast to GlobalID
```

**Cause:** Missing `@idOf` directive in schema.

**Fix:**
```graphql
# Wrong
input UpdateUserInput {
  id: ID!  # Missing @idOf
}

# Correct
input UpdateUserInput {
  id: ID! @idOf(type: "User")
}
```

Then regenerate code and update resolver.

### "NotImplementedError"

**Error:**
```
kotlin.NotImplementedError: An operation is not implemented
```

**Cause:** Resolver doesn't override `resolve` or `batchResolve`.

**Fix:**
```kotlin
// Wrong - neither method overridden
class UserNodeResolver : NodeResolvers.User()

// Correct
class UserNodeResolver : NodeResolvers.User() {
    override suspend fun resolve(ctx: Context): User {
        // Implementation
    }
}
```

### "Illegal base64 character"

**Error:**
```
IllegalArgumentException: Illegal base64 character
```

**Cause:** Passing internal ID where GlobalID expected, or vice versa.

**Fix:**
- Check if you're passing raw UUID instead of GlobalID
- Ensure `@idOf` is used in schema
- Don't manually encode/decode in resolvers

### "NullPointerException in policy executor"

**Error:**
```
NullPointerException at GroupMembershipExecutor.execute
```

**Cause:** Assuming GlobalID when it's actually String (policy executors get raw values).

**Fix:**
```kotlin
// Wrong
val groupId = (arguments["groupId"] as GlobalID<*>).internalID

// Correct
val groupId = when (val arg = arguments["groupId"]) {
    is GlobalID<*> -> arg.internalID
    is String -> decodeBase64GlobalId(arg)
    else -> error("Unexpected type")
}
```

## Authorization Errors

### "Policy not being executed"

**Symptoms:** Authorization passes when it should fail.

**Causes:**
1. Directive not applied in schema
2. Factory not registered
3. Wrong directive name

**Fix Checklist:**
- [ ] Directive defined: `directive @myPolicy on FIELD_DEFINITION`
- [ ] Directive applied: `@myPolicy` on field/type
- [ ] Factory registered in `configureSchema()`
- [ ] Build succeeded after changes

### "Authentication required"

**Error:**
```
RuntimeException: Authentication required: request context not found
```

**Cause:** Request missing auth headers or wrong context type.

**Fix:**
```kotlin
// Ensure proper context casting
val requestContext = context.requestContext as? GraphQLRequestContext
    ?: return PolicyError("Authentication required")
```

Check that frontend sends:
- `Authorization: Bearer <token>`
- `X-User-Id: <userId>`

## Data Errors

### "Node not found"

**Error:**
```
GraphQL Error: Node not found: VXNlcjoxMjM=
```

**Cause:** Entity doesn't exist in database.

**Fix:**
- Verify ID exists in database
- Check if using correct GlobalID (type name matches)
- Handle null case in resolver:
```kotlin
override suspend fun resolve(ctx: Context): User {
    val data = userService.fetch(ctx.id.internalID)
        ?: throw RuntimeException("User not found: ${ctx.id}")
    // ...
}
```

### "Batch response length mismatch"

**Error:**
```
IllegalStateException: batchResolve returned 5 items but expected 10
```

**Cause:** `batchResolve` return list doesn't match input contexts length.

**Fix:**
```kotlin
// Wrong
override suspend fun batchResolve(contexts: List<Context>): List<FieldValue<User>> {
    return userService.fetchBatch(contexts.map { it.id.internalID })
        .map { FieldValue.ofValue(buildUser(it)) }  // May return fewer items!
}

// Correct
override suspend fun batchResolve(contexts: List<Context>): List<FieldValue<User>> {
    val users = userService.fetchBatch(contexts.map { it.id.internalID })

    return contexts.map { ctx ->  // Map over contexts, not results
        val data = users[ctx.id.internalID]
        if (data == null) {
            FieldValue.ofError(RuntimeException("Not found"))
        } else {
            FieldValue.ofValue(buildUser(ctx, data))
        }
    }
}
```

## GraphQL Errors

### "Field 'x' not found on type 'Y'"

**Error:**
```
Validation error: Field 'displayName' not found on type 'User'
```

**Cause:** Field not in schema or wrong scope.

**Fix:**
- Verify field exists in schema
- Check scope matches request's schema ID
- Regenerate after schema changes

### "Null value for non-null field"

**Error:**
```
Cannot return null for non-nullable field User.email
```

**Cause:** Resolver returns null for `!` field.

**Fix:**
```kotlin
// Wrong - email might be null from database
.email(data.email)  // Throws if null

// Option 1: Make field nullable in schema
type User {
  email: String  # Nullable
}

// Option 2: Provide default or throw meaningful error
.email(data.email ?: throw RuntimeException("User has no email"))
```

## Debug Tips

### Enable Logging

```kotlin
@Resolver
class UserNodeResolver : NodeResolvers.User() {
    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun resolve(ctx: Context): User {
        logger.debug("Resolving user: ${ctx.id}")
        // ...
    }
}
```

### Test Queries in GraphiQL

Access at `http://localhost:8080/graphiql` to test queries interactively.

### Check Generated Code

Look at generated files to understand expected types:
```
build/generated-sources/viaduct/
├── grts/           # Generated types
├── resolverbases/  # Resolver base classes
└── ...
```

### Validate Schema Separately

```bash
./gradlew validateViaductSchema
```

## See Also

- [GlobalIDs](../gotchas/global-ids.md) - GlobalID-specific issues
- [Policy Checkers](../gotchas/policy-checkers.md) - Policy issues
- [Entities](../core/entities.md) - Resolver patterns
