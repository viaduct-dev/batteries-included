# Per-Row Policy Checks with Group Membership

This document describes the implementation of per-row policy checks using Viaduct's CheckerExecutor feature for group-based access control.

## Overview

The template implements a group-based access control system where:
- Users can create **groups**
- Groups have **members** (many-to-many relationship)
- Resources belong to **groups**
- **Per-row policy checks** verify group membership before allowing access

This demonstrates Viaduct's policy check feature with custom `@requiresGroupMembership` directive.

## Architecture

### Database Layer (RLS + Application Logic)
- `groups` table: stores group information
- `group_members` table: manages group memberships
- Resources have optional `group_id` foreign key
- RLS policies enforce group membership at the database level
- Application-level policy checks provide GraphQL-specific error messages

### GraphQL Layer (Viaduct Policy Checks)

#### 1. Custom Directive (`PolicyDirective.graphqls`)
```graphql
directive @requiresGroupMembership(
  groupIdField: String = "groupId"
) on FIELD_DEFINITION | OBJECT
```

Applied to types and fields that require group membership verification:
```graphql
type Resource implements Node @requiresGroupMembership(groupIdField: "groupId") {
  id: ID!
  title: String!
  groupId: String
  # ...
}

type Group implements Node @requiresGroupMembership(groupIdField: "id") {
  id: ID!
  name: String!
  # ...
}
```

#### 2. Policy Executor (`GroupMembershipPolicyExecutor.kt`)
Implements `CheckerExecutor` interface to perform the actual policy check:

```kotlin
class GroupMembershipPolicyExecutor(
    private val groupIdFieldName: String,
    private val groupService: GroupService
) : CheckerExecutor {
    override suspend fun execute(
        arguments: Map<String, Any?>,
        objectDataMap: Map<String, EngineObjectData>,
        context: EngineExecutionContext
    ): CheckerResult {
        // Extract user ID from request context
        val userId = (context.requestContext as GraphQLRequestContext).userId

        // Get group ID from object data or arguments
        val groupId = extractGroupId(objectDataMap, arguments)

        // Check membership via GroupService
        return if (groupService.isUserMemberOfGroup(userId, groupId)) {
            CheckerResult.Success
        } else {
            GroupMembershipErrorResult(
                RuntimeException("Access denied: You are not a member of this group")
            )
        }
    }
}
```

**Key Features:**
- Reads group ID from resolved object data
- Verifies user membership via database query
- Returns clear error messages on access denial
- Supports null group IDs for backward compatibility

#### 3. Checker Factory (`GroupMembershipCheckerFactory.kt`)
Implements `CheckerExecutorFactory` to create policy executors based on schema directives:

```kotlin
class GroupMembershipCheckerFactory(
    private val schema: ViaductSchema,
    private val groupService: GroupService
) : CheckerExecutorFactory {
    override fun checkerExecutorForField(
        typeName: String,
        fieldName: String
    ): CheckerExecutor? {
        val field = schema.schema.getObjectType(typeName)
            ?.getFieldDefinition(fieldName)
            ?: return null

        if (!field.hasAppliedDirective("requiresGroupMembership")) {
            return null
        }

        val directive = field.getAppliedDirective("requiresGroupMembership")
        val groupIdField = directive.getArgument("groupIdField")?.getValue() as? String ?: "groupId"

        return GroupMembershipPolicyExecutor(groupIdField, groupService)
    }

    override fun checkerExecutorForType(typeName: String): CheckerExecutor? {
        // Similar implementation for types
    }
}
```

#### 4. Group Service (`GroupService.kt`)
Provides membership verification and group management:

```kotlin
class GroupService(private val supabaseService: SupabaseService) {
    suspend fun isUserMemberOfGroup(userId: String, groupId: String): Boolean {
        // Query group_members table
        val members = /* database query */
        return members.isNotEmpty()
    }

    // Other group management methods...
}
```

## Policy Check Flow

When a GraphQL query requests a field with `@requiresGroupMembership`:

1. **Query Execution Starts**
   ```graphql
   query {
     resources {
       id
       title
       groupId
     }
   }
   ```

2. **Viaduct Identifies Policy Check**
   - Sees `Resource` type has `@requiresGroupMembership` directive
   - Calls `GroupMembershipCheckerFactory.checkerExecutorForType("Resource")`

3. **Factory Creates Executor**
   - Reads `groupIdField` parameter from directive
   - Creates `GroupMembershipPolicyExecutor` with appropriate configuration

4. **Policy Check Executes**
   - Extracts user ID from request context
   - Extracts group ID from resolved object data
   - Queries database for membership: `SELECT * FROM group_members WHERE user_id = ? AND group_id = ?`

5. **Result Handling**
   - **Success**: Field resolves normally
   - **Failure**: Field returns `null` with error in GraphQL response:
     ```json
     {
       "data": { "resources": null },
       "errors": [{
         "message": "Access denied: You are not a member of this group",
         "path": ["resources", 0]
       }]
     }
     ```

## Registration

To register the policy checker with Viaduct, use `StandardViaduct.Builder`:

```kotlin
import viaduct.service.runtime.StandardViaduct

val viaduct = StandardViaduct.Builder()
    .withTenantAPIBootstrapperBuilder(/* ... */)
    .withSchemaConfiguration(/* ... */)
    .withCheckerExecutorFactoryCreator { viaductSchema ->
        GroupMembershipCheckerFactory(viaductSchema, groupService)
    }
    .build()
```

### Alternative: Implement in Resolvers

Move policy checks into resolver logic as a workaround:
```kotlin
@Resolver
class ResourcesQueryResolver(
    private val groupService: GroupService
) : QueryResolvers.Resources() {
    override suspend fun resolve(ctx: Context): List<Resource> {
        val userId = (ctx.requestContext as GraphQLRequestContext).userId

        val items = /* fetch from database */

        // Manual policy check
        return items.filter { item ->
            item.groupId == null || groupService.isUserMemberOfGroup(userId, item.groupId)
        }
    }
}
```

## Benefits of This Approach

1. **Separation of Concerns**: Authorization logic is separate from business logic
2. **Declarative Security**: Use GraphQL directives to mark protected fields/types
3. **Per-Row Granularity**: Each object is checked individually
4. **Clear Error Messages**: Users understand why access was denied
5. **Reusable**: The `@requiresGroupMembership` directive can be applied to any type/field
6. **Type-Safe**: Leverages Viaduct's generated types and resolver bases

## Testing

Test policy checks using Viaduct's test utilities:

```kotlin
@Test
fun `access denied when not group member`() {
    val groupId = "group-123"
    val nonMemberUserId = "user-456"

    MockTenantModuleBootstrapper(SDL) {
        field("Query" to "resourcesByGroup") {
            resolver {
                fn { _, _, _, _, _ ->
                    listOf(Resource(id = "item-1", groupId = groupId))
                }
            }
            checker {
                fn { args, objectDataMap ->
                    val groupId = args["groupId"] as String
                    if (!groupService.isUserMemberOfGroup(nonMemberUserId, groupId)) {
                        throw RuntimeException("Access denied")
                    }
                }
            }
        }
    }.runFeatureTest {
        val result = viaduct.runQuery(
            "query { resourcesByGroup(groupId: \"$groupId\") { id } }",
            requestContext = GraphQLRequestContext(nonMemberUserId, "token")
        )

        assertNull(result.getData()["resourcesByGroup"])
        assertTrue(result.errors.isNotEmpty())
        assertTrue(result.errors[0].message.contains("Access denied"))
    }
}
```

## Key Files

### Database
- `schema/migrations/` - Migration files for groups, members, and RLS policies

### GraphQL Schema
- `backend/src/main/viaduct/schema/PolicyDirective.graphqls` - Custom directive
- `backend/src/main/viaduct/schema/Group.graphqls` - Group types and operations

### Kotlin Implementation
- `backend/src/main/kotlin/com/viaduct/policy/GroupMembershipPolicyExecutor.kt` - Policy executor
- `backend/src/main/kotlin/com/viaduct/policy/GroupMembershipCheckerFactory.kt` - Executor factory
- `backend/src/main/kotlin/com/viaduct/services/GroupService.kt` - Group operations and membership checks
- `backend/src/main/kotlin/com/viaduct/SupabaseClient.kt` - Group-related database methods
- `backend/src/main/kotlin/com/viaduct/config/KoinModule.kt` - Service registration

## Related Documentation

- [VIADUCT_POLICY_GUIDE.md](./VIADUCT_POLICY_GUIDE.md) - Complete policy directive guide
- [VIADUCT_GLOBALID_GUIDE.md](./VIADUCT_GLOBALID_GUIDE.md) - Working with GlobalIDs
- [docs/IMPLEMENTING_A_RESOURCE.md](./docs/IMPLEMENTING_A_RESOURCE.md) - Adding new resource types
