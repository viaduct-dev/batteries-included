# Viaduct Skill Validation Test Plan

This document defines features to implement in viaduct-batteries-included to validate that the Viaduct Claude Code skill provides sufficient documentation for development.

## Validation Methodology

For each feature:
1. **Clear the context** - Start with only the skill documentation loaded
2. **Implement the feature** - Using only skill documentation as reference
3. **Verify** - Build succeeds and feature works correctly
4. **Document gaps** - Note any missing information in the skill

## Test Features (mapped to tutorials)

---

### Feature 1: Simple Field Resolver (Tutorial 01)
**Covers:** Basic field resolver, @resolver directive, generated base classes

**Requirements:**
Add a computed field `memberCount` to the `Group` type that returns the number of members.

**Schema changes:**
```graphql
type Group implements Node {
  # ... existing fields ...
  memberCount: Int! @resolver  # NEW: computed from members
}
```

**Implementation:**
- Create `GroupMemberCountResolver` extending `GroupResolvers.MemberCount`
- Use required selection set to access group data
- Return count of members

**Success criteria:**
- Query `{ groups { id name memberCount } }` returns member counts
- Build passes without errors

**Status:** ✅ IMPLEMENTED (with Gap 2 noted)
- Schema updated: added `memberCount: Int! @resolver` to Group type
- Resolver created: `GroupMemberCountResolver` extends `GroupResolvers.MemberCount`
- Uses `@Resolver(objectValueFragment = "fragment _ on Group { id }")` pattern
- Accesses parent via `ctx.objectValue.getId().internalID`
- Build passes
- **Gap noted:** Skill shows `@Resolver("...")` but codebase uses `@Resolver(objectValueFragment = "...")`

---

### Feature 2: Node Resolver (Tutorial 02)
**Covers:** Node interface, GlobalID system, node resolution by ID

**Requirements:**
Add a `Tag` entity that can be fetched by GlobalID.

**Schema changes:**
```graphql
type Tag implements Node @scope(to: ["default"]) {
  id: ID!
  name: String!
  color: String
  createdAt: String!
}

extend type Query @scope(to: ["default"]) {
  tag(id: ID! @idOf(type: "Tag")): Tag @resolver
}
```

**Database migration:**
```sql
CREATE TABLE public.tags (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name TEXT NOT NULL,
  color TEXT,
  created_at TIMESTAMPTZ DEFAULT now()
);
```

**Implementation:**
- Create `TagNodeResolver` extending `NodeResolvers.Tag`
- Create `TagQueryResolver` for the query field
- Use `ctx.id.internalID` to fetch from database

**Success criteria:**
- Can query tag by GlobalID
- Node resolver correctly handles GlobalID

**Status:** ✅ IMPLEMENTED (with Gap 1 noted)
- Schema created: `Tag.graphqls`
- Resolver created: `TagQueryResolver` (uses `QueryResolvers.Tag()` not `NodeResolvers.Tag`)
- Service created: `TagService`
- Database migration created
- Build passes
- **Note:** Unable to fully test due to Supabase local PostgREST schema cache issue (infrastructure, not Viaduct)

---

### Feature 3: Combined Resolvers with Required Selection Set (Tutorial 03)
**Covers:** objectValueFragment, computed fields depending on other fields

**Requirements:**
Add a `displayName` field to `GroupMember` that combines user info.

**Schema changes:**
```graphql
type GroupMember {
  # ... existing fields ...
  displayName: String @resolver  # NEW: computed from user lookup
}
```

**Implementation:**
- Create field resolver with required selection set `fragment _ on GroupMember { userId }`
- Look up user name from userId
- Return formatted display name

**Success criteria:**
- Query `{ groups { members { displayName } } }` returns user names
- Required selection set correctly specified

**Status:** ✅ IMPLEMENTED
- Schema updated: added `displayName: String @resolver` to GroupMember type
- Resolver created: `GroupMemberDisplayNameResolver` extends `GroupMemberResolvers.DisplayName`
- Uses `@Resolver(objectValueFragment = "fragment _ on GroupMember { userId }")` pattern
- Accesses parent via `ctx.objectValue.getUserId()`
- Looks up user via `UserService.getUserById()` and returns email as display name
- Build passes (compilation successful)
- **No new gaps found** - skill documentation pattern worked correctly

---

### Feature 4: Batch Resolution (Tutorial 07 & 08)
**Covers:** N+1 problem, batchResolve, FieldValue

**Requirements:**
Add `tags` field to Group with batch resolution.

**Schema changes:**
```graphql
type Group implements Node {
  # ... existing fields ...
  tags: [Tag!]! @resolver  # NEW: with batch resolution
}

# Join table (not exposed in GraphQL)
# group_tags(group_id, tag_id)
```

**Database migration:**
```sql
CREATE TABLE public.group_tags (
  group_id UUID REFERENCES public.groups(id),
  tag_id UUID REFERENCES public.tags(id),
  PRIMARY KEY (group_id, tag_id)
);
```

**Implementation:**
- Create `GroupTagsResolver` with `batchResolve`
- Batch fetch all tag IDs for multiple groups
- Return `List<FieldValue<List<Tag>>>`

**Success criteria:**
- Query multiple groups with tags in single database call
- Correct handling of FieldValue return type

**Status:** ✅ IMPLEMENTED
- Schema updated: added `tags: [Tag!]! @resolver` to Group type
- Migration created: `group_tags` join table with RLS policies
- Created `GroupTagsResolver` extending `GroupResolvers.Tags()`
- Uses `batchResolve(contexts: List<Context>): List<FieldValue<List<Tag>>>` pattern
- Batch fetches tags for all groups in single database query
- Uses `contexts.first().authenticatedClient` for the batch call
- Maps results back to contexts preserving order with `FieldValue.ofValue()`
- Build passes (compilation successful)
- **No new gaps found** - skill documentation for batchResolve pattern was clear

---

### Feature 5: Mutations with GlobalID (Tutorial 05)
**Covers:** Mutation resolvers, @idOf directive, GlobalID in inputs

**Requirements:**
Add CRUD mutations for Tags with proper GlobalID handling.

**Schema changes:**
```graphql
input CreateTagInput @scope(to: ["default"]) {
  name: String!
  color: String
}

input UpdateTagInput @scope(to: ["default"]) {
  id: ID! @idOf(type: "Tag")  # CRITICAL: @idOf directive
  name: String
  color: String
}

extend type Mutation @scope(to: ["default"]) {
  createTag(input: CreateTagInput!): Tag! @resolver
  updateTag(input: UpdateTagInput!): Tag! @resolver
  deleteTag(id: ID! @idOf(type: "Tag")): Boolean! @resolver

  # Tag-Group association
  addTagToGroup(groupId: ID! @idOf(type: "Group"), tagId: ID! @idOf(type: "Tag")): Boolean! @resolver
  removeTagFromGroup(groupId: ID! @idOf(type: "Group"), tagId: ID! @idOf(type: "Tag")): Boolean! @resolver
}
```

**Implementation:**
- Create mutation resolvers using `input.id.internalID` (NOT manual Base64)
- Use `ctx.globalIDFor(Tag.Reflection, id)` for response objects
- Handle @idOf for all ID inputs

**Success criteria:**
- Create tag, extract ID, use in update mutation
- No manual Base64 encoding/decoding in resolvers
- Build passes with correct GlobalID types

**Status:** ✅ IMPLEMENTED
- Schema created with `CreateTagInput`, `UpdateTagInput` (with `@idOf`), and all mutations
- Resolvers created: `CreateTagResolver`, `UpdateTagResolver`, `DeleteTagResolver`
- All use `MutationResolvers.X` base classes
- Uses `input.id.internalID` pattern for GlobalID access
- Uses `ctx.globalIDFor(Tag.Reflection, id)` for response building
- Build passes with correct types
- Database migration updated with full RLS policies
- **Note:** Same PostgREST infrastructure issue blocks testing

---

### Feature 6: Scopes (Tutorial 06)
**Covers:** @scope directive, API visibility, multi-tenant architecture

**Requirements:**
Add admin-only fields and operations for Tags.

**Schema changes:**
```graphql
# Admin-only extension
extend type Tag @scope(to: ["admin"]) {
  internalNotes: String
  usageCount: Int @resolver
}

extend type Mutation @scope(to: ["admin"]) {
  deleteAllTags: Int! @resolver  # Admin-only bulk operation
}
```

**Implementation:**
- Fields only visible in admin scope
- Verify scope filtering works correctly

**Success criteria:**
- Admin fields not visible in default scope
- Admin mutations only callable with admin scope

**Status:** ✅ IMPLEMENTED
- Schema updated: added `extend type Tag @scope(to: ["admin"])` with `internalNotes` and `usageCount`
- Added admin-only `deleteAllTags` mutation
- Migration updated to add `internal_notes` column to tags table
- Created `TagUsageCountResolver` extending `TagResolvers.UsageCount()`
- Created `DeleteAllTagsResolver` extending `MutationResolvers.DeleteAllTags()`
- Added Supabase methods for `getTagUsageCount()` and `deleteAllTags()`
- Build passes (compilation successful)
- **No new gaps found** - skill documentation for @scope directive was clear

---

### Feature 7: Entity Relationships (Relationships doc)
**Covers:** Node references, ctx.nodeFor(), relationship patterns

**Requirements:**
Add `createdBy` relationship from Tag to User.

**Schema changes:**
```graphql
type Tag implements Node {
  # ... existing fields ...
  createdById: String!
  createdBy: User @resolver  # NEW: relationship to User
}
```

**Implementation:**
- Create field resolver returning `ctx.nodeFor(globalId)`
- NOT fetching user data directly - let Node Resolver handle it

**Success criteria:**
- Query `{ tags { createdBy { name } } }` works
- Uses node reference pattern (not direct fetch)

**Status:** ✅ IMPLEMENTED (with Gap 3 noted)
- Schema updated: added `createdById: String!` and `createdBy: User @resolver` to Tag type
- User type updated to `implements Node` (required for `ctx.nodeFor()` pattern)
- User query added: `user(id: ID! @idOf(type: "User")): User @resolver` for node resolution
- Created `UserQueryResolver` extending `QueryResolvers.User()`
- Created `TagCreatedByResolver` extending `TagResolvers.CreatedBy()`
- Uses `ctx.nodeFor(ctx.globalIDFor(User.Reflection, createdById))` pattern
- Updated existing User resolvers to use `ctx.globalIDFor()` (breaking change when adding Node)
- Build passes (compilation successful)
- **Gap noted:** Skill doesn't mention that the target type MUST implement Node for `ctx.nodeFor()` to work

---

### Feature 8: Policy Checker
**Status:** ❌ REMOVED - Not available to OSS consumers

Policy checkers require `viaduct.engine.api.*` classes (`CheckerExecutor`, `CheckerExecutorFactory`, etc.) which are NOT exposed in the published fat jar. The engine-api is an internal implementation detail, not a consumer-facing API.

Authorization for OSS consumers should be handled via:
- Database-level security (Row Level Security in Supabase/Postgres)
- Resolver-level checks (checking permissions in resolver code)
- Scope-based visibility (`@scope` directive)

---

## Implementation Order

1. **Feature 2: Tag Entity** - Creates base entity for other features
2. **Feature 5: Tag Mutations** - CRUD operations with GlobalID
3. **Feature 1: Member Count** - Simple computed field
4. **Feature 3: Display Name** - Required selection set pattern
5. **Feature 7: CreatedBy Relationship** - Node reference pattern
6. **Feature 4: Group Tags** - Batch resolution
7. **Feature 6: Admin Scopes** - Visibility control
8. ~~**Feature 8: Policy Checker**~~ - REMOVED (engine-api not exposed to OSS)

## Validation Checklist

After implementing each feature, verify:

- [ ] Used only skill documentation (no other references)
- [ ] Build passes: `./gradlew build`
- [ ] Feature works in GraphiQL
- [ ] No manual Base64 encoding/decoding in resolvers
- [ ] @idOf used on all input/argument ID fields

## Gap Documentation

Track any information missing from the skill:

| Feature | Gap Found | Severity | Skill File to Update | Status |
|---------|-----------|----------|---------------------|--------|
| Feature 2 | NodeResolvers vs QueryResolvers confusion | Medium | entities.md | ✅ FIXED |
| Feature 1 | @Resolver annotation syntax | N/A | entities.md | NOT A GAP - both forms valid |
| Feature 7 | ctx.nodeFor() requires Node interface | Medium | relationships.md | ✅ FIXED |
| Feature 8 | Policy Checker not available to OSS | N/A | policy-checkers.md | REMOVED - engine-api not exposed |

**Severity Levels:**
- **Blocker** - Cannot implement without additional info
- **Major** - Requires significant trial/error or external lookup
- **Minor** - Inconvenient but discoverable

---

## Remediation Process

**Strategy:** Collect ALL gaps during validation, then apply ONE comprehensive fix to the skill.

### During Validation: Document Every Gap

For each gap encountered, add an entry to the table above AND create a detailed record:

```markdown
### Gap: [Short description]

**Feature:** [Feature number and name]
**Severity:** Blocker | Major | Minor
**What I tried:** [What the skill said to do]
**What happened:** [Error or confusion]
**What was missing:** [Specific information needed]
**How I solved it:** [External source or trial/error]
**Suggested fix:** [What to add to skill, which file]
```

**Do NOT create PRs yet.** Continue validation using external sources as needed.

### After Validation: Comprehensive Skill Update

Once all 8 features are implemented:

1. **Review all gaps** - Identify patterns and prioritize
2. **Create single branch** for all fixes:
   ```bash
   cd /tmp/skills
   git checkout main && git pull
   git checkout -b fix/validation-gaps
   ```
3. **Apply all fixes** to appropriate skill files
4. **Create single PR** with all improvements
5. **Re-run validation** with updated skill (optional second pass)

### Gap Collection Template

Add gaps to this section as they're discovered:

---

#### Gaps Found

<!-- Add gaps here during validation -->

##### Gap 1: NodeResolvers vs QueryResolvers Confusion

**Feature:** 2 (Tag Entity / Node Resolver)
**Severity:** Medium
**File:** `viaduct/resources/core/entities.md` and `viaduct/resources/core/queries.md`

**Issue:** The skill documentation mentions `NodeResolvers.Listing()` (in queries.md batch resolution section) as the base class for node resolvers, but this codebase doesn't generate a `NodeResolvers` file. Instead, entities implementing `Node` are fetched via `QueryResolvers.EntityName()` like any other query.

The skill's `entities.md` shows:
```kotlin
class UserNodeResolver : NodeResolvers.User() {
```

But this codebase uses:
```kotlin
class TagQueryResolver : QueryResolvers.Tag() {
```

**Impact:** Confusion about which pattern to use. The `NodeResolvers` pattern may be for a different Viaduct version or configuration.

**Suggested Fix:** Clarify when `NodeResolvers` vs `QueryResolvers` should be used, or remove the `NodeResolvers` references if they don't apply to the standard Viaduct setup.

##### Gap 2: @Resolver Annotation Syntax Discrepancy

**Feature:** 1 (Simple Field Resolver - memberCount)
**Severity:** Low
**File:** `viaduct/resources/core/entities.md`

**Issue:** The skill documentation shows the required selection set as a direct string parameter to `@Resolver`:

```kotlin
@Resolver("fragment _ on User { firstName lastName }")
class UserDisplayNameResolver : UserResolvers.DisplayName() {
```

But this codebase uses a named parameter `objectValueFragment`:

```kotlin
@Resolver(objectValueFragment = "fragment _ on Group { id }")
class GroupMembersResolver : GroupResolvers.Members() {
```

**Impact:** Minor confusion when following the documentation. Both forms may work, but the documentation should show the canonical/preferred form.

**Suggested Fix:** Verify which form is correct and update the documentation to match, or document both forms if both are valid.

##### Gap 3: ctx.nodeFor() Requires Target Type to Implement Node

**Feature:** 7 (Entity Relationships - createdBy)
**Severity:** Medium
**File:** `viaduct/resources/core/relationships.md`

**Issue:** The relationships documentation shows using `ctx.nodeFor()` to create node references:

```kotlin
return ctx.nodeFor(ctx.globalIDFor(User.Reflection, authorId))
```

However, it doesn't mention that:
1. The target type (User in this case) MUST implement the `Node` interface
2. The target type MUST have a query endpoint (e.g., `user(id: ID!): User @resolver`) for node resolution to work
3. All existing resolvers for that type will need to be updated to use `ctx.globalIDFor()` instead of plain String IDs

When User didn't implement Node, it had a plain `id: ID!` field that accepted String. After adding `implements Node`, the generated `User.Builder.id()` method expects `GlobalID<User>` instead, which is a breaking change for existing code.

**Impact:** Developer could follow the relationship pattern correctly but get confusing compile errors about GlobalID type mismatches if the target type doesn't already implement Node.

**Suggested Fix:** Add a note to relationships.md explaining:
1. The target type must implement Node
2. You may need to add a query endpoint for the target type
3. Existing resolvers may need updating when adding Node to a type

##### Gap 4: Policy Checker Not Available to OSS Consumers - REMOVED

**Feature:** 8 (Policy Checker)
**Severity:** N/A - Feature removed from skill
**File:** `viaduct/resources/gotchas/policy-checkers.md` - DELETED

**Finding:** Policy checkers require `viaduct.engine.api.*` classes (`CheckerExecutor`, `CheckerExecutorFactory`, `CheckerResult`, etc.) which are NOT exposed in the published Viaduct fat jar. The engine-api module is used as an `implementation` dependency internally, meaning its types are not available to consumers.

**Resolution:** Removed all policy checker documentation from the skill. OSS consumers should use alternative authorization approaches:
- Database-level security (Row Level Security)
- Resolver-level permission checks
- Scope-based visibility (`@scope` directive)

---

## Skill Repo Reference

**Repo:** https://github.com/viaduct-dev/skills
**Local clone:** /tmp/skills

**File mapping for fixes:**

| Issue Type | Skill File |
|------------|------------|
| Node resolver patterns | `viaduct/resources/core/entities.md` |
| Query/field resolvers | `viaduct/resources/core/queries.md` |
| Mutation patterns | `viaduct/resources/core/mutations.md` |
| Relationship patterns | `viaduct/resources/core/relationships.md` |
| GlobalID issues | `viaduct/resources/gotchas/global-ids.md` |
| Build/runtime errors | `viaduct/resources/reference/troubleshooting.md` |
| Schema design | `viaduct/resources/planning/schema-design.md` |
| Missing from overview | `viaduct/SKILL.md` |

---

## Database Migrations Summary

All migrations for this validation:

```sql
-- migrations/YYYYMMDDHHMMSS_add_tags.sql

-- Tags table
CREATE TABLE public.tags (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name TEXT NOT NULL,
  color TEXT,
  created_by_id UUID REFERENCES auth.users(id),
  internal_notes TEXT,  -- Admin only
  created_at TIMESTAMPTZ DEFAULT now(),
  updated_at TIMESTAMPTZ DEFAULT now()
);

-- Group-Tag join table
CREATE TABLE public.group_tags (
  group_id UUID REFERENCES public.groups(id) ON DELETE CASCADE,
  tag_id UUID REFERENCES public.tags(id) ON DELETE CASCADE,
  created_at TIMESTAMPTZ DEFAULT now(),
  PRIMARY KEY (group_id, tag_id)
);

-- Indexes
CREATE INDEX idx_tags_created_by ON public.tags(created_by_id);
CREATE INDEX idx_group_tags_group ON public.group_tags(group_id);
CREATE INDEX idx_group_tags_tag ON public.group_tags(tag_id);

-- RLS
ALTER TABLE public.tags ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.group_tags ENABLE ROW LEVEL SECURITY;
```
