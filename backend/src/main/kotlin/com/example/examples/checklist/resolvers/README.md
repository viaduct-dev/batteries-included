# Checklist example

These are optional, library-based resolvers, not a second database client.

1. Copy the accompanying ChecklistItem schema into `backend/src/main/viaduct/schema`, removing
   the `.example` suffix.
2. Copy these five resolver files into `backend/src/main/kotlin/com/example/resolvers`, removing
   the `.example` suffix. No uncommenting is needed.
3. Register them with `singleOf` in KoinModule.
4. Generate the effective model and review the database migration. The SQL example describes
   the application defaults, Auth foreign key and access policies. Apply generated PostgreSQL
   migration SQL and pg_graphql metadata after creating the table.
5. Add the new resource's application checker before exposing it. The existing Group checker
   does not automatically protect new types; the SQL example retains database membership checks.

The Group.checklistItems relationship replaces the old field resolver and filtered root query.
Query `group(id: ...) { checklistItems { ... } }` to get a group's items.

Create and update return `{ checklistItem { ... } }` payloads. Delete returns a payload with
an empty `userErrors` list on success; database failures appear in the GraphQL `errors` array.
No per-resource DTO, JSON mapper or Supabase database methods are needed.

See the root `docs/IMPLEMENTING_A_RESOURCE.md` for the full workflow.

The accompanying `backend/src/test/kotlin/com/example/examples/checklist/ChecklistPersistenceIntegrationTest.kt.example`
can be copied to `backend/src/test/kotlin/com/example/ChecklistPersistenceIntegrationTest.kt`.
After enabling the example and applying its migrations, run
`./gradlew test --tests com.example.ChecklistPersistenceIntegrationTest` against local Supabase.
