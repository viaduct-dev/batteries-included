# Adding a persistent resource

Define the resource in the Viaduct schema, use pg-persistence to generate its database model,
and write the application resolvers. Do not add a PostgREST client or copy GraphQL transport,
JSON encoding, or GRT-building code into the application.

## Define the schema

For example, add `ChecklistItem.graphqls` under `backend/src/main/viaduct/schema`:

```graphql
type ChecklistItem implements Node @scope(to: ["default"]) @resolver(isSelective: true) {
  id: ID!
  title: String!
  completed: Boolean!
  groupId: ID! @idOf(type: "Group")
}

input CreateChecklistItemInput @scope(to: ["default"]) {
  title: String!
  completed: Boolean!
  groupId: ID! @idOf(type: "Group")
}

input UpdateChecklistItemInput @scope(to: ["default"]) {
  id: ID! @idOf(type: "ChecklistItem")
  title: String
  completed: Boolean
}

type ChecklistItemPayload @scope(to: ["default"]) {
  checklistItem: ChecklistItem!
}

extend type Query @scope(to: ["default"]) {
  checklistItems: [ChecklistItem!]! @resolver
}

extend type Mutation @scope(to: ["default"]) {
  createChecklistItem(input: CreateChecklistItemInput!): ChecklistItemPayload! @resolver
  updateChecklistItem(input: UpdateChecklistItemInput!): ChecklistItemPayload! @resolver
}
```

A `Node` is persistent by default. `@idOf` records the target of a UUID foreign key.
Inputs contain the stored fields that callers may change; they are not inferred from mutation names.
Nested input objects do not create related rows automatically.

For a database-backed collection relationship, add a list field such as
`Group.checklistItems: [ChecklistItem!]!`. Its inverse is the item's `groupId`.
The node resolver resolves this relationship through pg-persistence, which follows database
cursors and returns all node references; no field resolver or DTO mapping is needed.
Prefer a paginated connection for potentially large collections to bound each request.

## Generate and review the migration

From `backend`:

```bash
./gradlew buildViaductEffectiveModel
```

Review `build/generated/viaduct-effective-model/META-INF/`. Use the relational output
and `postgresql-migration.sql` to prepare an application migration in `schema/migrations/`.
Apply `pg-graphql-metadata.sql` after the tables and constraints exist. The plugin generates
SQL; it does not apply migrations or supply application authorization.

Do not replay the complete fresh-schema bundle against an existing deployment. Renames, defaults,
indexes, backfills, Auth foreign keys and delete behavior must be reviewed in the migration.
Use `hibernateSchemaDiff` against an existing database when preparing an upgrade.

## Write the resolvers

Inject the existing `DbClient` from Koin. The runtime takes owned selections from
the selective node context, including requests through `node(id: ...)`:

```kotlin
@Resolver
class ChecklistItemNodeResolver(private val dbClient: DbClient) : NodeResolvers.ChecklistItem() {
    override suspend fun resolve(ctx: Context): ChecklistItem =
        dbClient.fetchByInternalId(
            ctx, "checklistItemCollection", ctx.id.internalID,
        )
}
```

A mutation explicitly converts its generated input and delegates the insert or update:

```kotlin
@Resolver
class CreateChecklistItemResolver(private val dbClient: DbClient) : MutationResolvers.CreateChecklistItem() {
    override suspend fun resolve(ctx: Context): ChecklistItemPayload =
        dbClient.entity<ChecklistItem>().insert(ctx, ctx.arguments.input.toPgGraphqlInsert())
}

@Resolver
class UpdateChecklistItemResolver(private val dbClient: DbClient) : MutationResolvers.UpdateChecklistItem() {
    override suspend fun resolve(ctx: Context): ChecklistItemPayload =
        dbClient.entity<ChecklistItem>().update(ctx, ctx.arguments.input.toPgGraphqlUpdate<ChecklistItem>())
}
```

Import `DbClient`, `toPgGraphqlInsert`, and `toPgGraphqlUpdate` from
`dev.viaduct.persistence.runtime.db`, and `Resolver` from `viaduct.api.resolver`.
Register each resolver with `singleOf(::ResolverClass)` in `KoinModule.kt`.

The library builds the payload's `checklistItem` reference. Keep business checks in the resolver
or a checker executor. Updates use the input's `@idOf(type: "ChecklistItem")` field as their
identifier; if several fields match, pass `identifierField` explicitly when converting.
Omitted fields are unchanged; explicit null is sent to PostgreSQL and may violate a not-null constraint.

For an unpaged list, follow `GroupsQueryResolver`: call the authenticated facade's
`selectNodeIds("ChecklistItem")` and return a node reference for each ID. For bounded
paging, use Viaduct OSS `@connection` and `@edge` schema types, then call
`dbClient.fetchConnection(ctx, DbRead(DbRoot("checklistItemCollection")), ctx.selections())`.
Paging arguments and cursors come exclusively from Viaduct.
For delete and transaction examples, see the
[pg-persistence README](https://github.com/viaduct-dev/pg-persistence/blob/main/README.md).

## Keep authorization in the application

The existing group checker protects `Group`, not arbitrary new resource types. Add a checker
for the new resource that obtains its `groupId` and verifies membership. Do not use the obsolete
`@requiresGroupMembership` directive.

Keep database policies as an additional safeguard. For example, the new table's select/insert/update/
delete policies can check `public.is_group_member(group_id)`. The existing application forwards the
user's access token; it must not substitute a service-role token in resolver database calls.
pg-persistence does not generate permission rules or grant admin bypass.

## Query from the frontend

Use the existing `executeGraphQL` helper:

```graphql
mutation CreateItem($input: CreateChecklistItemInput!) {
  createChecklistItem(input: $input) {
    checklistItem { id title completed groupId }
  }
}
```

Read the object from `data.createChecklistItem.checklistItem`. Treat IDs as opaque values; pass
them back in typed ID inputs without manually encoding or decoding them.

## Test the feature

Exercise the public Viaduct operation against local Supabase with the production checker factory
enabled. Cover successful inserts and reads, updates preserving omitted fields, explicit nulls,
unauthorized access, missing rows, pagination, and any database defaults or cascades.

`GroupPersistenceIntegrationTest` demonstrates this for the built-in resource. The optional
checklist schema, SQL and resolver examples follow the same pg-persistence setup.
