-- pg_graphql cannot expose anonymous TABLE results or void mutation results.
-- Wrappers preserve the existing application-owned authorization checks.
CREATE FUNCTION public.get_all_users_json() RETURNS jsonb
LANGUAGE sql STABLE SECURITY INVOKER SET search_path = ''
AS $$
  SELECT coalesce(jsonb_agg(to_jsonb(u)), '[]'::jsonb) FROM public.get_all_users() u
$$;

CREATE FUNCTION public.search_users_json(search_query text) RETURNS jsonb
LANGUAGE sql STABLE SECURITY INVOKER SET search_path = ''
AS $$
  SELECT coalesce(jsonb_agg(to_jsonb(u)), '[]'::jsonb) FROM public.search_users(search_query) u
$$;

CREATE FUNCTION public.set_user_admin_graphql(target_user_id uuid, is_admin boolean) RETURNS boolean
LANGUAGE plpgsql VOLATILE SECURITY INVOKER SET search_path = ''
AS $$
BEGIN
  PERFORM public.set_user_admin(target_user_id, is_admin);
  RETURN true;
END;
$$;

REVOKE ALL ON FUNCTION public.get_all_users_json() FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.search_users_json(text) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.set_user_admin_graphql(uuid, boolean) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.get_all_users_json() TO authenticated;
GRANT EXECUTE ON FUNCTION public.search_users_json(text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.set_user_admin_graphql(uuid, boolean) TO authenticated;

-- This test helper deletes all groups; never expose it to application users.
REVOKE ALL ON FUNCTION public.cleanup_test_data() FROM PUBLIC, anon, authenticated;
