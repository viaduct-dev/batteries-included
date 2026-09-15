-- OPTIONAL EXAMPLE: not automatically applied because it is in the examples subdirectory.
-- Copy to a real timestamped migration after enabling the schema and resolvers.
-- Review generated pg-persistence SQL for schema changes; this adds application defaults/policies.
CREATE TABLE public.checklist_items (
    _uuid_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id uuid NOT NULL REFERENCES public.groups(_uuid_id) ON DELETE CASCADE,
    user_id uuid NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
    title text NOT NULL,
    completed boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX checklist_items_group_id_idx ON public.checklist_items(group_id);
ALTER TABLE public.checklist_items ENABLE ROW LEVEL SECURITY;
CREATE POLICY checklist_member_select ON public.checklist_items FOR SELECT
    USING (public.is_group_member(group_id));
CREATE POLICY checklist_member_insert ON public.checklist_items FOR INSERT
    WITH CHECK (public.is_group_member(group_id) AND user_id = auth.uid());
CREATE POLICY checklist_member_update ON public.checklist_items FOR UPDATE
    USING (public.is_group_member(group_id)) WITH CHECK (public.is_group_member(group_id));
CREATE POLICY checklist_member_delete ON public.checklist_items FOR DELETE
    USING (public.is_group_member(group_id));
CREATE TRIGGER checklist_updated_at BEFORE UPDATE ON public.checklist_items
    FOR EACH ROW EXECUTE FUNCTION public.update_groups_updated_at();

-- Then apply the generated postgresql-migration.sql and pg-graphql-metadata.sql.
-- They add the generated global ID and GraphQL relationship metadata.
