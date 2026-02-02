-- Add created_by_id and internal_notes to tags table
ALTER TABLE public.tags ADD COLUMN created_by_id UUID REFERENCES auth.users(id);
ALTER TABLE public.tags ADD COLUMN internal_notes TEXT;

-- Group-Tag join table
CREATE TABLE public.group_tags (
  group_id UUID REFERENCES public.groups(id) ON DELETE CASCADE,
  tag_id UUID REFERENCES public.tags(id) ON DELETE CASCADE,
  created_at TIMESTAMPTZ DEFAULT now(),
  PRIMARY KEY (group_id, tag_id)
);

-- Indexes
CREATE INDEX idx_group_tags_group ON public.group_tags(group_id);
CREATE INDEX idx_group_tags_tag ON public.group_tags(tag_id);

-- Enable RLS
ALTER TABLE public.group_tags ENABLE ROW LEVEL SECURITY;

-- Policy: Users can view group_tags for groups they're members of
CREATE POLICY "Users can view group_tags for their groups"
  ON public.group_tags
  FOR SELECT
  TO authenticated
  USING (
    EXISTS (
      SELECT 1 FROM public.group_members
      WHERE group_members.group_id = group_tags.group_id
      AND group_members.user_id = auth.uid()
    )
  );

-- Policy: Group members can add tags to groups
CREATE POLICY "Group members can add tags"
  ON public.group_tags
  FOR INSERT
  TO authenticated
  WITH CHECK (
    EXISTS (
      SELECT 1 FROM public.group_members
      WHERE group_members.group_id = group_tags.group_id
      AND group_members.user_id = auth.uid()
    )
  );

-- Policy: Group members can remove tags from groups
CREATE POLICY "Group members can remove tags"
  ON public.group_tags
  FOR DELETE
  TO authenticated
  USING (
    EXISTS (
      SELECT 1 FROM public.group_members
      WHERE group_members.group_id = group_tags.group_id
      AND group_members.user_id = auth.uid()
    )
  );

-- Grants
GRANT SELECT, INSERT, DELETE ON public.group_tags TO authenticated;
