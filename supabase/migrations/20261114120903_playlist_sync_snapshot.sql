BEGIN;

CREATE TABLE public.playlist_sync_revisions (
    user_id uuid PRIMARY KEY REFERENCES public.profiles(id) ON DELETE CASCADE,
    revision bigint NOT NULL CHECK (revision > 0)
);
ALTER TABLE public.playlist_sync_revisions ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.playlist_sync_revisions FROM PUBLIC, anon, authenticated;
GRANT SELECT ON TABLE public.playlist_sync_revisions TO authenticated;
CREATE POLICY playlist_sync_revision_owner ON public.playlist_sync_revisions
    FOR SELECT TO authenticated USING (user_id = (SELECT auth.uid()));

-- Function callable only through the triggers. Legacy REST/RPC and direct Data API writes
-- advance the same revision; account deletion needs no surviving revision.
CREATE FUNCTION public.aura_advance_playlist_revision()
RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE
    owners uuid[];
BEGIN
    IF TG_TABLE_NAME = 'playlists' THEN
        IF TG_OP = 'INSERT' THEN owners := ARRAY[NEW.user_id];
        ELSIF TG_OP = 'DELETE' THEN owners := ARRAY[OLD.user_id];
        ELSE owners := ARRAY[OLD.user_id, NEW.user_id];
        END IF;
    ELSE
        IF TG_OP = 'INSERT' THEN
            SELECT ARRAY(SELECT p.user_id FROM public.playlists p WHERE p.id = NEW.playlist_id) INTO owners;
        ELSIF TG_OP = 'DELETE' THEN
            SELECT ARRAY(SELECT p.user_id FROM public.playlists p WHERE p.id = OLD.playlist_id) INTO owners;
        ELSE
            SELECT ARRAY(SELECT p.user_id FROM public.playlists p WHERE p.id IN (OLD.playlist_id, NEW.playlist_id)) INTO owners;
        END IF;
    END IF;
    INSERT INTO public.playlist_sync_revisions(user_id, revision)
    SELECT DISTINCT profile.id, 1
    FROM public.profiles profile
    WHERE profile.id = ANY(owners)
    ORDER BY profile.id
    ON CONFLICT (user_id) DO UPDATE
        SET revision = playlist_sync_revisions.revision + 1;
    RETURN NULL;
END;
$function$;
REVOKE ALL ON FUNCTION public.aura_advance_playlist_revision() FROM PUBLIC, anon, authenticated;

-- Install the triggers before backfilling, with both writes in this migration transaction.
CREATE TRIGGER aura_playlist_revision
    AFTER INSERT OR UPDATE OR DELETE ON public.playlists
    FOR EACH ROW EXECUTE FUNCTION public.aura_advance_playlist_revision();
CREATE TRIGGER aura_playlist_item_revision
    AFTER INSERT OR UPDATE OR DELETE ON public.playlist_items
    FOR EACH ROW EXECUTE FUNCTION public.aura_advance_playlist_revision();
INSERT INTO public.playlist_sync_revisions(user_id, revision)
SELECT DISTINCT user_id, 1 FROM public.playlists;

CREATE FUNCTION public.aura_playlist_snapshot()
RETURNS jsonb
LANGUAGE sql STABLE SECURITY INVOKER SET search_path = ''
AS $function$
    WITH owned_playlists AS (
        SELECT p.id, p.user_id, p.name, p.cover_uri, p.is_pinned, p.created_at, p.updated_at,
               COALESCE(items.entries, '[]'::jsonb) AS items,
               items.item_count
        FROM public.playlists p
        CROSS JOIN LATERAL (
            SELECT jsonb_agg(to_jsonb(i) ORDER BY i.position) AS entries,
                   count(*) AS item_count
            FROM public.playlist_items i
            WHERE i.playlist_id = p.id
        ) items
        WHERE p.user_id = (SELECT auth.uid())
    )
    SELECT jsonb_build_object(
        'user_id', (SELECT auth.uid()),
        'contract_version', 1,
        'revision', COALESCE((
            SELECT revision FROM public.playlist_sync_revisions WHERE user_id = (SELECT auth.uid())
        ), 0),
        'playlist_count', count(*),
        'item_count', COALESCE(sum(p.item_count), 0),
        'playlists', COALESCE(
            jsonb_agg(to_jsonb(p) ORDER BY p.id),
            '[]'::jsonb
        )
    )
    FROM owned_playlists p;
$function$;

REVOKE ALL ON FUNCTION public.aura_playlist_snapshot() FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.aura_playlist_snapshot() TO authenticated;

COMMIT;
