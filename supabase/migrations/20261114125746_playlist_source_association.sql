BEGIN;

ALTER TABLE public.playlists
    ADD COLUMN source_provider text,
    ADD COLUMN source_external_id text,
    ADD COLUMN source_revision bigint NOT NULL DEFAULT 0 CHECK (source_revision >= 0),
    ADD CONSTRAINT playlist_source_reference CHECK (
        (source_provider IS NULL AND source_external_id IS NULL)
        OR (source_provider IS NOT NULL AND source_external_id IS NOT NULL AND (
            (source_provider = 'spotify' AND source_external_id ~ '^[A-Za-z0-9]{22}$')
            OR (source_provider = 'deezer' AND source_external_id ~ '^[1-9][0-9]{0,19}$')
        ))
    );

CREATE FUNCTION public.aura_advance_playlist_source_revision()
RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path = ''
AS $function$
BEGIN
    IF TG_OP = 'INSERT' THEN
        NEW.source_revision := CASE WHEN NEW.source_provider IS NULL THEN 0 ELSE 1 END;
    ELSIF (NEW.source_provider, NEW.source_external_id)
          IS DISTINCT FROM (OLD.source_provider, OLD.source_external_id) THEN
        NEW.source_revision := OLD.source_revision + 1;
    ELSE
        NEW.source_revision := OLD.source_revision;
    END IF;
    RETURN NEW;
END
$function$;
REVOKE ALL ON FUNCTION public.aura_advance_playlist_source_revision() FROM PUBLIC, anon, authenticated;
CREATE TRIGGER aura_playlist_source_revision
    BEFORE INSERT OR UPDATE ON public.playlists
    FOR EACH ROW EXECUTE FUNCTION public.aura_advance_playlist_source_revision();

CREATE FUNCTION public.aura_set_playlist_source(
    p_playlist_id text, p_provider text, p_external_id text, p_expected_revision bigint
) RETURNS jsonb
LANGUAGE plpgsql SECURITY INVOKER SET search_path = ''
AS $function$
DECLARE
    owner_id uuid := auth.uid();
    parent public.playlists%ROWTYPE;
BEGIN
    SELECT * INTO parent FROM public.playlists
    WHERE id = p_playlist_id AND user_id = owner_id FOR UPDATE;
    IF NOT FOUND THEN
        RETURN jsonb_build_object('status', 'not_found');
    END IF;
    IF parent.source_provider IS DISTINCT FROM p_provider
        OR parent.source_external_id IS DISTINCT FROM p_external_id THEN
        IF parent.source_revision IS DISTINCT FROM p_expected_revision THEN
            RETURN jsonb_build_object('status', 'conflict');
        END IF;
        UPDATE public.playlists
        SET source_provider = p_provider, source_external_id = p_external_id
        WHERE id = p_playlist_id AND user_id = owner_id
        RETURNING * INTO parent;
    END IF;
    RETURN jsonb_build_object('status', 'applied', 'provider', parent.source_provider,
        'external_id', parent.source_external_id, 'source_revision', parent.source_revision);
END
$function$;
REVOKE ALL ON FUNCTION public.aura_set_playlist_source(text,text,text,bigint) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.aura_set_playlist_source(text,text,text,bigint) TO authenticated;

CREATE OR REPLACE FUNCTION public.aura_playlist_snapshot()
RETURNS jsonb
LANGUAGE sql STABLE SECURITY INVOKER SET search_path = ''
AS $function$
    WITH owned_playlists AS (
        SELECT p.id, p.user_id, p.name, p.cover_uri, p.is_pinned, p.created_at, p.updated_at,
               p.source_provider, p.source_external_id, p.source_revision,
               COALESCE(items.entries, '[]'::jsonb) AS items, items.item_count
        FROM public.playlists p
        CROSS JOIN LATERAL (
            SELECT jsonb_agg(to_jsonb(i) ORDER BY i.position) AS entries, count(*) AS item_count
            FROM public.playlist_items i
            WHERE i.playlist_id = p.id
        ) items
        WHERE p.user_id = (SELECT auth.uid())
    )
    SELECT jsonb_build_object(
        'user_id', (SELECT auth.uid()), 'contract_version', 1,
        'revision', COALESCE((
            SELECT revision FROM public.playlist_sync_revisions WHERE user_id = (SELECT auth.uid())
        ), 0),
        'playlist_count', count(*), 'item_count', COALESCE(sum(p.item_count), 0),
        'playlists', COALESCE(jsonb_agg(to_jsonb(p) ORDER BY p.id), '[]'::jsonb)
    )
    FROM owned_playlists p;
$function$;

COMMIT;
