# Viaduct Policy Check Guide

This guide provides comprehensive instructions for creating and using policy checks in Viaduct. All examples are self-contained and ready to use.

## What are Policy Checks?

Policy checks in Viaduct allow you to enforce authorization rules declaratively using custom directives in your GraphQL schema. They execute before resolvers run and can:

- Check field-level permissions (queries, mutations)
- Check type-level permissions (per-row authorization)
- Access arguments, object data, and request context
- Block unauthorized access before expensive operations

## Architecture Overview

Policy checks consist of three components:

1. **GraphQL Directive Definition** (`.graphqls` file)
2. **Policy Executor** (Kotlin class implementing `CheckerExecutor`)
3. **Policy Factory** (Kotlin class implementing `CheckerExecutorFactory`)

```
GraphQL Schema (@directive)
        ↓
Policy Factory (reads directive, creates executor)
        ↓
Policy Executor (performs authorization check)
        ↓
Resolver (only runs if policy passes)
```

## Complete Example: Group Membership Policy

This complete example shows how to create a policy that checks if a user is a member of a group before allowing access.

### Step 1: Define GraphQL Directive

Create `PolicyDirective.graphqls`:

```graphql
"""
Directive to enforce group membership policy checks.
Only users who are members of the group can access the field or type.
"""
directive @requiresGroupMembership(
  """
  The name of the field containing the group ID to check membership against.
  If not specified, defaults to 'groupId'.
  """
  groupIdField: String = "groupId"
) on FIELD_DEFINITION | OBJECT
```

**Key Points:**
- `on FIELD_DEFINITION` - Can be applied to queries/mutations/fields
- `on OBJECT` - Can be applied to types for per-row checks
- Arguments can have default values
- Use clear documentation strings

### Step 2: Apply Directive in Schema

Create `ChecklistItem.graphqls`:

```graphql
"""
A checklist item that belongs to a checkbox group.
Only members of the group can access the item.
"""
type ChecklistItem implements Node
  @scope(to: ["default"])
  @requiresGroupMembership(groupIdField: "groupId") {
  """
  The unique identifier for the item.
  """
  id: ID!

  """
  The title of the checklist item.
  """
  title: String!

  """
  Whether the item is completed.
  """
  completed: Boolean!

  """
  The ID of the user who created this item.
  """
  userId: String!

  """
  The ID of the checkbox group this item belongs to.
  If null, this is a legacy personal item.
  """
  groupId: String

  """
  When the item was created.
  """
  createdAt: String!

  """
  When the item was last updated.
  """
  updatedAt: String!
}

"""
Input for updating a checklist item.
"""
input UpdateChecklistItemInput @scope(to: ["default"]) {
  """
  The ID of the item to update.
  """
  id: ID!

  """
  The new completion status.
  """
  completed: Boolean

  """
  The new title.
  """
  title: String
}

extend type Query @scope(to: ["default"]) {
  """
  Get checklist items for a specific group.
  Only accessible if the user is a member of the group.
  """
  checklistItemsByGroup(groupId: ID!): [ChecklistItem!]!
    @resolver
    @requiresGroupMembership
}

extend type Mutation @scope(to: ["default"]) {
  """
  Update a checklist item.
  Only members of the item's group can update it.
  """
  updateChecklistItem(input: UpdateChecklistItemInput!): ChecklistItem!
    @resolver
    @requiresGroupMembership
}
```

**Understanding the Application:**

- **Type-level**: `@requiresGroupMembership(groupIdField: "groupId")` on `ChecklistItem` type
  - Checks every `ChecklistItem` object returned
  - If query returns 10 items, policy runs 10 times
  - Reads `groupId` from each object's data

- **Field-level**: `@requiresGroupMembership` on `checklistItemsByGroup` query
  - Checks once before resolver executes
  - Reads `groupId` from query arguments

- **Field-level with input**: `@requiresGroupMembership` on `updateChecklistItem` mutation
  - Checks once before resolver executes
  - Extracts item ID from input, fetches item, checks its groupId

### Step 3: Implement Policy Executor

Create `GroupMembershipPolicyExecutor.kt`:

```kotlin
package com.viaduct.policy

import com.viaduct.GraphQLRequestContext
import com.viaduct.services.GroupService
import viaduct.api.globalid.GlobalID
import viaduct.engine.api.CheckerExecutor
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.RequiredSelectionSet
import java.util.Base64

/**
 * Policy executor that checks if a user is a member of a checkbox group.
 * This demonstrates Viaduct's per-row policy check capabilities.
 *
 * The executor reads the group ID from either:
 * 1. Query/mutation arguments (field-level check)
 * 2. Object data (type-level check for each returned object)
 */
class GroupMembershipPolicyExecutor(
    private val groupIdFieldName: String,
    private val groupService: GroupService
) : CheckerExecutor {

    /**
     * Specify which fields must be selected for the policy check to work.
     * For simplicity, we're not requiring selection sets in this implementation.
     * The group ID will be extracted from object data if available.
     */
    override val requiredSelectionSets: Map<String, RequiredSelectionSet?> = emptyMap()

    override suspend fun execute(
        arguments: Map<String, Any?>,
        objectDataMap: Map<String, EngineObjectData>,
        context: EngineExecutionContext
    ): CheckerResult {
        // Extract the request context containing user information
        val requestContext = context.requestContext as? GraphQLRequestContext
            ?: return GroupMembershipErrorResult(
                RuntimeException("Authentication required: request context not found")
            )

        val userId = requestContext.userId

        // Get the object data (empty string key means current object)
        val objectData = objectDataMap[""]

        // CASE 1: Field-level check (no object data)
        // This happens when the policy is applied to a query/mutation
        if (objectData == null) {
            return checkFieldLevelPolicy(arguments, userId, context)
        }

        // CASE 2: Type-level check (per-row, has object data)
        // This happens when the policy is applied to a type
        return checkTypeLevelPolicy(objectData, userId, context)
    }

    /**
     * Check field-level policy (e.g., query arguments).
     * Examples:
     * - checklistItemsByGroup(groupId: "abc123")
     * - updateChecklistItem(input: { id: "xyz" })
     */
    private suspend fun checkFieldLevelPolicy(
        arguments: Map<String, Any?>,
        userId: String,
        context: EngineExecutionContext
    ): CheckerResult {
        // Try to get groupId from direct argument
        val groupIdArg = arguments[groupIdFieldName]
        if (groupIdArg != null) {
            val internalGroupId = extractInternalId(groupIdArg)
            return checkGroupMembership(userId, internalGroupId, context)
        }

        // Try to get groupId from input object (for mutations)
        val inputArg = arguments["input"]
        if (inputArg != null) {
            val itemId = extractIdFromInput(inputArg)
            if (itemId != null) {
                // Fetch the item to get its groupId
                val groupId = fetchGroupIdForItem(itemId, context)
                if (groupId != null) {
                    return checkGroupMembership(userId, groupId, context)
                }
            }
        }

        // No group ID found - allow access (might be a query that returns all groups)
        return CheckerResult.Success
    }

    /**
     * Check type-level policy (per-row).
     * This is called for each object in the result set.
     */
    private suspend fun checkTypeLevelPolicy(
        objectData: EngineObjectData,
        userId: String,
        context: EngineExecutionContext
    ): CheckerResult {
        // Extract the group ID from the object data
        val groupId = try {
            objectData.fetch(groupIdFieldName) as? String
        } catch (e: Exception) {
            // Field not found or null - this might be a legacy item without a group
            null
        }

        // If group ID is null, allow access (backward compatibility for personal items)
        if (groupId == null) {
            return CheckerResult.Success
        }

        // Check if the user is a member of the group
        return checkGroupMembership(userId, groupId, context)
    }

    /**
     * Extract internal ID from GlobalID or String argument.
     * Handles both:
     * - GlobalID<T> objects (from Viaduct deserialization)
     * - Base64-encoded GlobalID strings
     * - Plain UUID strings
     */
    private fun extractInternalId(arg: Any): String {
        return when (arg) {
            is GlobalID<*> -> {
                // ✅ CORRECT: Use .internalID property
                arg.internalID
            }
            is String -> {
                // Try to decode base64-encoded GlobalID string
                try {
                    val decoded = String(Base64.getDecoder().decode(arg))
                    decoded.substringAfter(":")
                } catch (e: Exception) {
                    // If decoding fails, assume it's already an internal ID
                    arg
                }
            }
            else -> throw IllegalArgumentException(
                "Expected GlobalID or String for argument '$groupIdFieldName' but got ${arg::class.java.name}"
            )
        }
    }

    /**
     * Extract ID from input object using reflection.
     * This handles mutation inputs like:
     * input UpdateChecklistItemInput {
     *   id: ID!
     *   title: String
     * }
     */
    private fun extractIdFromInput(inputArg: Any): String? {
        return try {
            val idField = inputArg::class.java.getMethod("getId")
            val idValue = idField.invoke(inputArg)

            // Ensure it's a GlobalID type
            if (idValue !is GlobalID<*>) {
                throw IllegalArgumentException(
                    "Expected GlobalID for input.id but got ${idValue?.let { it::class.java.name } ?: "null"}"
                )
            }
            // ✅ CORRECT: Use .internalID property
            idValue.internalID
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Fetch the groupId for a given item ID.
     * This is necessary when checking mutations like updateChecklistItem
     * where we only have the item ID in the input.
     */
    private suspend fun fetchGroupIdForItem(
        itemId: String,
        context: EngineExecutionContext
    ): String? {
        val requestContext = context.requestContext as? GraphQLRequestContext
            ?: return null
        val client = groupService.supabaseService.getAuthenticatedClient(requestContext)

        return try {
            val item = client.getChecklistItemById(itemId)
            item?.group_id
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Perform the actual membership check.
     * This is the core authorization logic.
     */
    private suspend fun checkGroupMembership(
        userId: String,
        groupId: String,
        context: EngineExecutionContext
    ): CheckerResult {
        val isMember = try {
            val requestContext = context.requestContext as? GraphQLRequestContext
                ?: return GroupMembershipErrorResult(
                    RuntimeException("Authentication required: request context not found")
                )
            val client = groupService.supabaseService.getAuthenticatedClient(requestContext)
            groupService.isUserMemberOfGroup(userId, groupId, client)
        } catch (e: Exception) {
            return GroupMembershipErrorResult(
                RuntimeException("Failed to check group membership: ${e.message}", e)
            )
        }

        return if (isMember) {
            CheckerResult.Success
        } else {
            GroupMembershipErrorResult(
                RuntimeException("Access denied: You are not a member of this group")
            )
        }
    }
}

/**
 * Error result for group membership policy check failures.
 */
class GroupMembershipErrorResult(
    override val error: Exception
) : CheckerResult.Error {

    /**
     * Should this error be returned to the resolver?
     * true = resolver will receive the error and can handle it
     * false = error is silently filtered (object removed from results)
     */
    override fun isErrorForResolver(ctx: viaduct.engine.api.CheckerResultContext): Boolean = true

    /**
     * How to combine field and type errors when both fail.
     * Field error takes precedence in this implementation.
     */
    override fun combine(fieldResult: CheckerResult.Error): CheckerResult.Error {
        return fieldResult
    }
}
```

### Step 4: Implement Policy Factory

Create `GroupMembershipCheckerFactory.kt`:

```kotlin
package com.viaduct.policy

import com.viaduct.services.GroupService
import viaduct.engine.api.CheckerExecutor
import viaduct.engine.api.CheckerExecutorFactory
import viaduct.engine.api.ViaductSchema

/**
 * Factory that creates CheckerExecutor instances for group membership policy checks.
 * This reads the @requiresGroupMembership directive from the GraphQL schema
 * and creates appropriate policy executors.
 *
 * The factory is called by Viaduct during schema initialization to register
 * policy executors for fields and types that have the directive.
 */
class GroupMembershipCheckerFactory(
    private val schema: ViaductSchema,
    private val groupService: GroupService
) : CheckerExecutorFactory {

    private val graphQLSchema = schema.schema

    /**
     * Create a policy executor for a specific field.
     * Returns null if the field doesn't have the @requiresGroupMembership directive.
     *
     * Called for queries, mutations, and type fields.
     */
    override fun checkerExecutorForField(
        typeName: String,
        fieldName: String
    ): CheckerExecutor? {
        val field = graphQLSchema.getObjectType(typeName)
            ?.getFieldDefinition(fieldName)
            ?: return null

        // Check if the field has the @requiresGroupMembership directive
        if (!field.hasAppliedDirective("requiresGroupMembership")) {
            return null
        }

        val directive = field.getAppliedDirective("requiresGroupMembership")
        return createExecutorFromDirective(directive)
    }

    /**
     * Create a policy executor for a specific type.
     * Returns null if the type doesn't have the @requiresGroupMembership directive.
     *
     * Called for types to enable per-row authorization checks.
     */
    override fun checkerExecutorForType(
        typeName: String
    ): CheckerExecutor? {
        val type = graphQLSchema.getObjectType(typeName)
            ?: return null

        // Check if the type has the @requiresGroupMembership directive
        if (!type.hasAppliedDirective("requiresGroupMembership")) {
            return null
        }

        val directive = type.getAppliedDirective("requiresGroupMembership")
        return createExecutorFromDirective(directive)
    }

    /**
     * Create a GroupMembershipPolicyExecutor from a directive.
     * Reads the groupIdField argument from the directive.
     */
    private fun createExecutorFromDirective(
        directive: graphql.schema.GraphQLAppliedDirective
    ): CheckerExecutor {
        // Read the groupIdField argument, defaulting to "groupId"
        val groupIdField = directive.getArgument("groupIdField")
            ?.getValue() as? String ?: "groupId"

        return GroupMembershipPolicyExecutor(
            groupIdFieldName = groupIdField,
            groupService = groupService
        )
    }
}
```

### Step 5: Register Policy Factory

Register your factory in your configuration. If using Koin for dependency injection:

Create `KoinTenantCodeInjector.kt`:

```kotlin
package com.viaduct.config

import com.viaduct.policy.GroupMembershipCheckerFactory
import com.viaduct.services.GroupService
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import viaduct.engine.api.ViaductSchema
import viaduct.spring.ViaductTenantCodeInjector

/**
 * Koin-based configuration injector for Viaduct.
 * This is where you register policy factories and other schema customizations.
 */
class KoinTenantCodeInjector : ViaductTenantCodeInjector, KoinComponent {
    private val groupService: GroupService by inject()

    override fun configureSchema(viaductSchema: ViaductSchema) {
        // Register the policy checker factory
        val checkerFactory = GroupMembershipCheckerFactory(viaductSchema, groupService)
        viaductSchema.registerCheckerExecutorFactory(checkerFactory)
    }
}
```

If using Spring's constructor injection instead:

```kotlin
package com.viaduct.config

import com.viaduct.policy.GroupMembershipCheckerFactory
import com.viaduct.services.GroupService
import org.springframework.stereotype.Component
import viaduct.engine.api.ViaductSchema
import viaduct.spring.ViaductTenantCodeInjector

@Component
class SpringTenantCodeInjector(
    private val groupService: GroupService
) : ViaductTenantCodeInjector {

    override fun configureSchema(viaductSchema: ViaductSchema) {
        // Register the policy checker factory
        val checkerFactory = GroupMembershipCheckerFactory(viaductSchema, groupService)
        viaduct viaductSchema.registerCheckerExecutorFactory(checkerFactory)
    }
}
```

### Step 6: Create Supporting Service

The policy executor needs a service to check group membership. Create `GroupService.kt`:

```kotlin
package com.viaduct.services

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.Serializable
import org.springframework.stereotype.Service

@Serializable
data class GroupMemberEntity(
    val id: String,
    val group_id: String,
    val user_id: String,
    val joined_at: String
)

@Service
class GroupService(
    val supabaseService: SupabaseService
) {
    /**
     * Check if a user is a member of a group.
     * Returns true if the user is a member, false otherwise.
     */
    suspend fun isUserMemberOfGroup(
        userId: String,
        groupId: String,
        client: SupabaseClient
    ): Boolean {
        return try {
            val members = client.from("group_members")
                .select {
                    filter {
                        eq("group_id", groupId)
                        eq("user_id", userId)
                    }
                }
                .decodeList<GroupMemberEntity>()

            members.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }
}
```

## Additional Policy Examples

### Example 1: Owner-Only Access Policy

**GraphQL Directive:**

```graphql
"""
Directive to enforce ownership checks.
Only the owner of a resource can access it.
"""
directive @requiresOwnership(
  """
  The name of the field containing the owner's user ID.
  Defaults to 'ownerId'.
  """
  ownerIdField: String = "ownerId"
) on FIELD_DEFINITION | OBJECT
```

**Schema Application:**

```graphql
type Document implements Node @requiresOwnership {
  id: ID!
  ownerId: String!
  title: String!
  content: String
}

extend type Mutation {
  deleteDocument(id: ID!): Boolean! @resolver @requiresOwnership
}
```

**Policy Executor:**

```kotlin
class OwnershipPolicyExecutor(
    private val ownerIdFieldName: String
) : CheckerExecutor {

    override val requiredSelectionSets: Map<String, RequiredSelectionSet?> = emptyMap()

    override suspend fun execute(
        arguments: Map<String, Any?>,
        objectDataMap: Map<String, EngineObjectData>,
        context: EngineExecutionContext
    ): CheckerResult {
        val requestContext = context.requestContext as? GraphQLRequestContext
            ?: return OwnershipErrorResult(RuntimeException("Authentication required"))

        val currentUserId = requestContext.userId
        val objectData = objectDataMap[""]

        // Type-level check
        if (objectData != null) {
            val ownerId = objectData.fetch(ownerIdFieldName) as? String
            return if (ownerId == currentUserId) {
                CheckerResult.Success
            } else {
                OwnershipErrorResult(RuntimeException("Access denied: You are not the owner"))
            }
        }

        // Field-level check - extract ID from arguments and fetch owner
        // Implementation similar to group membership check
        return CheckerResult.Success
    }
}

class OwnershipErrorResult(override val error: Exception) : CheckerResult.Error {
    override fun isErrorForResolver(ctx: viaduct.engine.api.CheckerResultContext): Boolean = true
    override fun combine(fieldResult: CheckerResult.Error): CheckerResult.Error = fieldResult
}
```

### Example 2: Role-Based Access Control

**GraphQL Directive:**

```graphql
"""
Directive to enforce role-based access control.
User must have the specified role to access the field.
"""
directive @requiresRole(
  """
  The required role name (e.g., "admin", "moderator").
  """
  role: String!
) on FIELD_DEFINITION
```

**Schema Application:**

```graphql
extend type Mutation {
  deleteUser(userId: String!): Boolean!
    @resolver
    @requiresRole(role: "admin")

  banUser(userId: String!): Boolean!
    @resolver
    @requiresRole(role: "moderator")
}
```

**Policy Executor:**

```kotlin
class RolePolicyExecutor(
    private val requiredRole: String,
    private val userService: UserService
) : CheckerExecutor {

    override val requiredSelectionSets: Map<String, RequiredSelectionSet?> = emptyMap()

    override suspend fun execute(
        arguments: Map<String, Any?>,
        objectDataMap: Map<String, EngineObjectData>,
        context: EngineExecutionContext
    ): CheckerResult {
        val requestContext = context.requestContext as? GraphQLRequestContext
            ?: return RoleErrorResult(RuntimeException("Authentication required"))

        val userId = requestContext.userId

        // Fetch user's roles from database
        val userRoles = try {
            userService.getUserRoles(userId)
        } catch (e: Exception) {
            return RoleErrorResult(RuntimeException("Failed to fetch user roles: ${e.message}"))
        }

        return if (userRoles.contains(requiredRole)) {
            CheckerResult.Success
        } else {
            RoleErrorResult(
                RuntimeException("Access denied: Requires '$requiredRole' role")
            )
        }
    }
}

class RoleErrorResult(override val error: Exception) : CheckerResult.Error {
    override fun isErrorForResolver(ctx: viaduct.engine.api.CheckerResultContext): Boolean = true
    override fun combine(fieldResult: CheckerResult.Error): CheckerResult.Error = fieldResult
}
```

**Factory for Role Policy:**

```kotlin
class RoleCheckerFactory(
    private val schema: ViaductSchema,
    private val userService: UserService
) : CheckerExecutorFactory {

    private val graphQLSchema = schema.schema

    override fun checkerExecutorForField(
        typeName: String,
        fieldName: String
    ): CheckerExecutor? {
        val field = graphQLSchema.getObjectType(typeName)
            ?.getFieldDefinition(fieldName)
            ?: return null

        if (!field.hasAppliedDirective("requiresRole")) {
            return null
        }

        val directive = field.getAppliedDirective("requiresRole")
        val requiredRole = directive.getArgument("role")?.getValue() as? String
            ?: return null

        return RolePolicyExecutor(requiredRole, userService)
    }

    override fun checkerExecutorForType(typeName: String): CheckerExecutor? {
        // Role checks don't apply to types in this implementation
        return null
    }
}
```

### Example 3: Multi-Condition Policy

**GraphQL Directive:**

```graphql
"""
Directive to enforce complex authorization rules.
User must be either the owner OR an admin OR a group member.
"""
directive @requiresComplexAuth(
  ownerIdField: String = "ownerId"
  groupIdField: String = "groupId"
) on FIELD_DEFINITION | OBJECT
```

**Policy Executor:**

```kotlin
class ComplexAuthPolicyExecutor(
    private val ownerIdFieldName: String,
    private val groupIdFieldName: String,
    private val userService: UserService,
    private val groupService: GroupService
) : CheckerExecutor {

    override val requiredSelectionSets: Map<String, RequiredSelectionSet?> = emptyMap()

    override suspend fun execute(
        arguments: Map<String, Any?>,
        objectDataMap: Map<String, EngineObjectData>,
        context: EngineExecutionContext
    ): CheckerResult {
        val requestContext = context.requestContext as? GraphQLRequestContext
            ?: return ComplexAuthErrorResult(RuntimeException("Authentication required"))

        val userId = requestContext.userId
        val objectData = objectDataMap[""]

        if (objectData == null) {
            // Field-level check - implement based on your needs
            return CheckerResult.Success
        }

        // Check 1: Is user an admin?
        val isAdmin = try {
            userService.isAdmin(userId)
        } catch (e: Exception) {
            false
        }

        if (isAdmin) {
            return CheckerResult.Success
        }

        // Check 2: Is user the owner?
        val ownerId = try {
            objectData.fetch(ownerIdFieldName) as? String
        } catch (e: Exception) {
            null
        }

        if (ownerId == userId) {
            return CheckerResult.Success
        }

        // Check 3: Is user a member of the group?
        val groupId = try {
            objectData.fetch(groupIdFieldName) as? String
        } catch (e: Exception) {
            null
        }

        if (groupId != null) {
            val client = groupService.supabaseService.getAuthenticatedClient(requestContext)
            val isMember = try {
                groupService.isUserMemberOfGroup(userId, groupId, client)
            } catch (e: Exception) {
                false
            }

            if (isMember) {
                return CheckerResult.Success
            }
        }

        // All checks failed
        return ComplexAuthErrorResult(
            RuntimeException("Access denied: Not authorized (not owner, admin, or group member)")
        )
    }
}

class ComplexAuthErrorResult(override val error: Exception) : CheckerResult.Error {
    override fun isErrorForResolver(ctx: viaduct.engine.api.CheckerResultContext): Boolean = true
    override fun combine(fieldResult: CheckerResult.Error): CheckerResult.Error = fieldResult
}
```

## Best Practices

### 1. Always Handle GlobalID Arguments Correctly

```kotlin
// ✅ CORRECT: Handle both GlobalID objects and strings
private fun extractInternalId(arg: Any): String {
    return when (arg) {
        is GlobalID<*> -> arg.internalID  // Use .internalID property
        is String -> tryDecodeGlobalId(arg) ?: arg
        else -> throw IllegalArgumentException("Unexpected type: ${arg::class.java.name}")
    }
}

// ❌ WRONG: Manual base64 decoding everywhere
val decoded = String(Base64.getDecoder().decode(arg as String))
val id = decoded.substringAfter(":")
```

### 2. Provide Clear, Actionable Error Messages

```kotlin
// ✅ CORRECT: Clear message explaining what went wrong
return GroupMembershipErrorResult(
    RuntimeException("Access denied: You are not a member of group '$groupId'")
)

// ❌ WRONG: Vague error messages
return GroupMembershipErrorResult(RuntimeException("Unauthorized"))
```

### 3. Handle Null/Optional Fields Gracefully

```kotlin
// ✅ CORRECT: Decide behavior for null fields
val groupId = objectData.fetch(groupIdFieldName) as? String
if (groupId == null) {
    // Option A: Allow access (backward compatibility)
    return CheckerResult.Success

    // Option B: Deny access (strict mode)
    // return ErrorResult(RuntimeException("Group ID required"))
}

// ❌ WRONG: Let null pointer exceptions happen
val groupId = objectData.fetch(groupIdFieldName) as String  // May throw!
```

### 4. Use Type-Safe Context Casting

```kotlin
// ✅ CORRECT: Safe cast with error handling
val requestContext = context.requestContext as? GraphQLRequestContext
    ?: return ErrorResult(RuntimeException("Authentication required"))

// ❌ WRONG: Unsafe cast that can throw
val requestContext = context.requestContext as GraphQLRequestContext
```

### 5. Separate Concerns in Complex Policies

```kotlin
// ✅ CORRECT: Break down complex checks into helper methods
override suspend fun execute(...): CheckerResult {
    if (objectData == null) {
        return checkFieldLevelPolicy(arguments, userId, context)
    }
    return checkTypeLevelPolicy(objectData, userId, context)
}

private suspend fun checkFieldLevelPolicy(...): CheckerResult { /* ... */ }
private suspend fun checkTypeLevelPolicy(...): CheckerResult { /* ... */ }

// ❌ WRONG: One massive execute() method with deeply nested logic
```

### 6. Cache Expensive Checks When Appropriate

```kotlin
// For per-row checks that might be called many times,
// consider caching within a single request
class GroupMembershipPolicyExecutor(...) : CheckerExecutor {
    private val membershipCache = mutableMapOf<Pair<String, String>, Boolean>()

    private suspend fun checkGroupMembership(
        userId: String,
        groupId: String,
        context: EngineExecutionContext
    ): CheckerResult {
        val cacheKey = userId to groupId

        // Check cache first
        val cachedResult = membershipCache[cacheKey]
        if (cachedResult != null) {
            return if (cachedResult) CheckerResult.Success else ErrorResult(...)
        }

        // Perform actual check and cache result
        val isMember = groupService.isUserMemberOfGroup(userId, groupId, client)
        membershipCache[cacheKey] = isMember

        return if (isMember) CheckerResult.Success else ErrorResult(...)
    }
}
```

## Common Pitfalls

### Pitfall 1: Not Handling Both Field and Type Level Checks

```kotlin
// ❌ WRONG: Only handles one case
override suspend fun execute(...): CheckerResult {
    val groupId = arguments["groupId"] as String  // Fails for type-level checks
    return checkMembership(groupId)
}

// ✅ CORRECT: Handles both cases
override suspend fun execute(...): CheckerResult {
    val objectData = objectDataMap[""]
    if (objectData == null) {
        return checkFieldLevelPolicy(arguments, ...)
    }
    return checkTypeLevelPolicy(objectData, ...)
}
```

### Pitfall 2: Treating GlobalID as String

```kotlin
// ❌ WRONG: Manual decoding
val groupId = String(Base64.getDecoder().decode(ctx.arguments.groupId))
    .substringAfter(":")

// ✅ CORRECT: Use Viaduct's type system
val groupId = ctx.arguments.groupId.internalID
```

### Pitfall 3: Forgetting to Register Factory

```kotlin
// ❌ WRONG: Factory created but never registered
class KoinTenantCodeInjector : ViaductTenantCodeInjector {
    override fun configureSchema(viaductSchema: ViaductSchema) {
        // Empty - factory not registered!
    }
}

// ✅ CORRECT: Register all factories
class KoinTenantCodeInjector : ViaductTenantCodeInjector {
    override fun configureSchema(viaductSchema: ViaductSchema) {
        viaductSchema.registerCheckerExecutorFactory(
            GroupMembershipCheckerFactory(viaductSchema, groupService)
        )
    }
}
```

### Pitfall 4: Not Testing Policy Failure Cases

Always test both success and failure:

```kotlin
@Test
fun `authorized user can access resource`() {
    // Test CheckerResult.Success
}

@Test
fun `unauthorized user cannot access resource`() {
    // Test CheckerResult.Error with appropriate message
}

@Test
fun `unauthenticated request is rejected`() {
    // Test missing authentication
}

@Test
fun `null group ID allows access`() {
    // Test backward compatibility
}
```

## Debugging Checklist

When your policy isn't working:

- [ ] Directive is defined in a `.graphqls` file
- [ ] Directive name in `@requiresGroupMembership` matches directive definition exactly
- [ ] Directive is applied to fields/types in schema with `@` prefix
- [ ] Factory implements `CheckerExecutorFactory` interface
- [ ] Factory is registered in `configureSchema()` method
- [ ] Executor handles both `objectData == null` (field-level) and `objectData != null` (type-level)
- [ ] GlobalIDs are extracted using `.internalID` property
- [ ] Error results return clear, descriptive messages
- [ ] Required services (like `GroupService`) are injected correctly
- [ ] Build succeeded and schema was regenerated (run `./gradlew build`)

## Complete Checklist for New Policy

When creating a new policy directive:

- [ ] GraphQL directive defined in `.graphqls` file with clear documentation
- [ ] Directive applied to appropriate fields/types in schema
- [ ] Policy executor implements `CheckerExecutor` interface
- [ ] Executor handles field-level checks (no object data)
- [ ] Executor handles type-level checks (with object data)
- [ ] Executor correctly extracts GlobalIDs using `.internalID`
- [ ] Error results implement `CheckerResult.Error` with clear messages
- [ ] Policy factory implements `CheckerExecutorFactory` interface
- [ ] Factory reads directive arguments correctly
- [ ] Factory registered in `configureSchema()` method
- [ ] Service dependencies injected via constructor
- [ ] Tests written for authorized access
- [ ] Tests written for unauthorized access
- [ ] Tests written for edge cases (null values, missing auth, etc.)
- [ ] Build passes: `./gradlew build`
- [ ] Policy tested in GraphiQL or API client
