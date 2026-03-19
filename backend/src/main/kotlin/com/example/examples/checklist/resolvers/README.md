# Checklist Resolvers Example

This directory contains commented example resolvers that demonstrate how to implement CRUD operations for a resource type using the GraphQL Policy Checker framework.

## Overview

The checklist implementation shows a complete example of:
- Creating resources within groups
- Updating resources with partial updates
- Deleting resources
- Querying all resources accessible to a user
- Querying resources filtered by group
- Field resolvers for nested relationships

## Files in This Directory

### Mutation Resolvers

- **CreateChecklistItemResolver.kt.example** - Creating a new resource in a group
- **UpdateChecklistItemResolver.kt.example** - Updating an existing resource
- **DeleteChecklistItemResolver.kt.example** - Deleting a resource

### Query Resolvers

- **ChecklistItemsQueryResolver.kt.example** - Querying all accessible resources
- **ChecklistItemsByGroupQueryResolver.kt.example** - Querying resources by group

### Field Resolvers

- **CheckboxGroupChecklistItemsResolver.kt.example** - Nested field resolution for group resources

## How to Use These Examples

1. **Study the examples** - Read through the commented code to understand the patterns
2. **Copy the files** - Copy relevant examples to `backend/src/main/kotlin/com/viaduct/resolvers/`
3. **Rename** - Change filenames and class names to match your resource type
4. **Uncomment** - Remove the comment markers (`/*` and `*/`)
5. **Customize** - Update types, field names, and logic for your specific resource
6. **Implement database methods** - Add corresponding methods to `SupabaseClient.kt`

## Key Patterns Demonstrated

### Authentication & Authorization
- User ID extraction from JWT context (`ctx.userId`)
- Authenticated client access (`ctx.authenticatedClient`)
- Automatic RLS policy enforcement
- Admin bypass support

### GlobalID Handling
- Decoding GlobalIDs to UUIDs (`.internalID`)
- Generating GlobalIDs for responses (`ctx.globalIDFor()`)
- Reusing GlobalIDs from input (performance optimization)

### Data Mapping
- Converting database entities to GraphQL types
- Using builder pattern for GraphQL objects
- Handling nullable fields

### Group-Based Access Control
- Foreign key relationships to groups
- Policy enforcement via `@requiresGroupMembership`
- Multi-layer security (RLS + backend policies)

## Related Documentation

- [GraphQL Schema Example](../../viaduct/schema/examples/checklist/ChecklistItem.graphqls.example)
- [Database Migration Example](../../../../../supabase/migrations/examples/checklist/)
- [Framework Overview](../../../../../docs/FRAMEWORK_OVERVIEW.md)
- [Implementation Guide](../../../../../docs/IMPLEMENTING_A_RESOURCE.md)

## Prerequisites

Before implementing your resolvers, ensure you have:

1. ✅ Defined your GraphQL schema with `@scope` and policy directives
2. ✅ Created database table with RLS policies
3. ✅ Implemented `SupabaseClient` methods for your resource
4. ✅ User is authenticated (JWT token present)

## Support

For more information on implementing custom resources, see:
- `docs/IMPLEMENTING_A_RESOURCE.md` - Step-by-step implementation guide
- `docs/AUTHORIZATION.md` - Understanding the authorization system
- `docs/GROUPS_AND_MEMBERSHIP.md` - Working with groups
