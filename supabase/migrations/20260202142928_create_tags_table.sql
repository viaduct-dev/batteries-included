-- Tags table
CREATE TABLE public.tags (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name TEXT NOT NULL,
  color TEXT,
  created_at TIMESTAMPTZ DEFAULT now()
);

-- Enable RLS
ALTER TABLE public.tags ENABLE ROW LEVEL SECURITY;

-- Policy: Allow all authenticated users to read tags
CREATE POLICY "Users can view all tags"
  ON public.tags
  FOR SELECT
  TO authenticated
  USING (true);

-- Policy: Allow anon to view tags
CREATE POLICY "Anon can view all tags"
  ON public.tags
  FOR SELECT
  TO anon
  USING (true);

-- Policy: Allow authenticated users to create tags
CREATE POLICY "Users can create tags"
  ON public.tags
  FOR INSERT
  TO authenticated
  WITH CHECK (true);

-- Policy: Allow authenticated users to update tags
CREATE POLICY "Users can update tags"
  ON public.tags
  FOR UPDATE
  TO authenticated
  USING (true);

-- Policy: Allow authenticated users to delete tags
CREATE POLICY "Users can delete tags"
  ON public.tags
  FOR DELETE
  TO authenticated
  USING (true);

-- Grants
GRANT SELECT ON public.tags TO authenticated;
GRANT SELECT ON public.tags TO anon;
GRANT INSERT, UPDATE, DELETE ON public.tags TO authenticated;
