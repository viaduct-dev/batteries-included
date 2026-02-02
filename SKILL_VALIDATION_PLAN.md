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

---

### Feature 8: Policy Checker (Policy Checkers gotcha)
**Covers:** Custom directive, CheckerExecutor, CheckerExecutorFactory, GlobalID in policies

**Requirements:**
Add `@requiresTagOwnership` policy for tag mutations.

**Schema changes:**
```graphql
directive @requiresTagOwnership(tagIdField: String = "id") on FIELD_DEFINITION

extend type Mutation {
  updateTag(input: UpdateTagInput!): Tag! @resolver @requiresTagOwnership(tagIdField: "id")
  deleteTag(id: ID! @idOf(type: "Tag")): Boolean! @resolver @requiresTagOwnership
}
```

**Implementation:**
- Create `TagOwnershipExecutor` implementing `CheckerExecutor`
- Handle BOTH `GlobalID<*>` AND `String` types (policy runs before deserialization)
- Create `TagOwnershipCheckerFactory` implementing `CheckerExecutorFactory`
- Register factory in `configureSchema()`

**Success criteria:**
- Non-owner cannot update/delete tags
- Owner can update/delete their tags
- Factory is registered (common mistake)
- Handles GlobalID correctly in policy context

---

## Implementation Order

1. **Feature 2: Tag Entity** - Creates base entity for other features
2. **Feature 5: Tag Mutations** - CRUD operations with GlobalID
3. **Feature 1: Member Count** - Simple computed field
4. **Feature 3: Display Name** - Required selection set pattern
5. **Feature 7: CreatedBy Relationship** - Node reference pattern
6. **Feature 4: Group Tags** - Batch resolution
7. **Feature 6: Admin Scopes** - Visibility control
8. **Feature 8: Policy Checker** - Authorization (most complex)

## Validation Checklist

After implementing each feature, verify:

- [ ] Used only skill documentation (no other references)
- [ ] Build passes: `./gradlew build`
- [ ] Feature works in GraphiQL
- [ ] No manual Base64 encoding/decoding in resolvers
- [ ] @idOf used on all input/argument ID fields
- [ ] Policy executors handle both GlobalID and String types
- [ ] Policy factories registered in configureSchema()

## Gap Documentation

Track any information missing from the skill:

| Feature | Gap Found | Severity | Skill File to Update | Status |
|---------|-----------|----------|---------------------|--------|
| | | | | |

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

*No gaps recorded yet.*

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
| Policy checker issues | `viaduct/resources/gotchas/policy-checkers.md` |
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
