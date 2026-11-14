BEGIN;

ALTER TABLE public.playlist_items ADD COLUMN source_metadata jsonb
    CHECK (source_metadata IS NULL OR jsonb_typeof(source_metadata) = 'object');

CREATE TABLE public.playlist_source_exclusions (
    playlist_id text NOT NULL REFERENCES public.playlists(id) ON DELETE CASCADE,
    track_id text NOT NULL,
    metadata jsonb,
    PRIMARY KEY (playlist_id, track_id)
);
ALTER TABLE public.playlist_source_exclusions ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.playlist_source_exclusions FROM PUBLIC, anon, authenticated;

CREATE TABLE public.playlist_refresh_results (
    playlist_id text NOT NULL REFERENCES public.playlists(id) ON DELETE CASCADE,
    request_id uuid NOT NULL,
    source_revision bigint NOT NULL,
    completed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    result jsonb NOT NULL,
    PRIMARY KEY (playlist_id, request_id)
);
CREATE INDEX playlist_refresh_results_latest ON public.playlist_refresh_results
    (playlist_id, source_revision, completed_at DESC, request_id DESC);
ALTER TABLE public.playlist_refresh_results ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.playlist_refresh_results FROM PUBLIC, anon, authenticated;
GRANT SELECT ON TABLE public.playlist_refresh_results TO authenticated;
CREATE POLICY playlist_refresh_result_owner ON public.playlist_refresh_results
    FOR SELECT TO authenticated USING (EXISTS (
        SELECT 1 FROM public.playlists p WHERE p.id = playlist_id AND p.user_id = (SELECT auth.uid())
    ));

-- A manual removal from either client records the exact identity before the
-- occurrence vanishes. A cascade removing the parent has no exclusion to retain.
CREATE FUNCTION public.aura_record_playlist_exclusion()
RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
BEGIN
    INSERT INTO public.playlist_source_exclusions(playlist_id, track_id, metadata)
    SELECT p.id, OLD.track_id, OLD.source_metadata
    FROM public.playlists p WHERE p.id = OLD.playlist_id
    ON CONFLICT (playlist_id, track_id) DO UPDATE
        SET metadata = COALESCE(playlist_source_exclusions.metadata, EXCLUDED.metadata);
    RETURN OLD;
END
$function$;
REVOKE ALL ON FUNCTION public.aura_record_playlist_exclusion() FROM PUBLIC, anon, authenticated;
CREATE TRIGGER aura_playlist_record_exclusion
    BEFORE DELETE ON public.playlist_items
    FOR EACH ROW EXECUTE FUNCTION public.aura_record_playlist_exclusion();

-- Historical entries can lack provider metadata. Keep their displayed identity
-- only as a candidate for explicit review after a manual removal.
ALTER FUNCTION public.aura_apply_playlist_operation(text,text,text,text,jsonb) RENAME TO aura_apply_playlist_operation_v2;
REVOKE ALL ON FUNCTION public.aura_apply_playlist_operation_v2(text,text,text,text,jsonb) FROM authenticated;

CREATE FUNCTION public.aura_apply_playlist_operation(
    p_operation_id text, p_entity_type text, p_entity_id text, p_operation_type text, p_payload jsonb
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE
    owner_id uuid := auth.uid();
    parent_id text := p_payload->>'playlist_id';
    recording_id text;
    outcome jsonb;
BEGIN
    IF owner_id IS NULL THEN RAISE EXCEPTION USING ERRCODE = '42501'; END IF;
    IF p_entity_type = 'playlist_item' AND p_operation_type = 'delete'
        AND length(btrim(p_payload->>'removed_title')) BETWEEN 1 AND 512
        AND length(btrim(p_payload->>'removed_artist')) BETWEEN 1 AND 512 THEN
        PERFORM 1 FROM public.playlists WHERE id = parent_id AND user_id = owner_id FOR UPDATE;
        IF FOUND THEN
            SELECT i.track_id INTO recording_id FROM public.playlist_items i
            WHERE i.playlist_id = parent_id AND i.id = p_entity_id;
        END IF;
    END IF;
    outcome := public.aura_apply_playlist_operation_v2(
        p_operation_id, p_entity_type, p_entity_id, p_operation_type, p_payload
    );
    IF recording_id IS NOT NULL AND outcome->>'status' = 'applied' THEN
        UPDATE public.playlist_source_exclusions
        SET metadata = jsonb_build_object('title', btrim(p_payload->>'removed_title'),
            'artist', btrim(p_payload->>'removed_artist'))
        WHERE playlist_id = parent_id AND track_id = recording_id AND metadata IS NULL;
    END IF;
    RETURN outcome;
END
$function$;
REVOKE ALL ON FUNCTION public.aura_apply_playlist_operation(text,text,text,text,jsonb) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.aura_apply_playlist_operation(text,text,text,text,jsonb) TO authenticated;

CREATE FUNCTION public.aura_commit_playlist_refresh(
    p_playlist_id text, p_request_id uuid, p_source_revision bigint, p_tracks jsonb,
    p_confirmed_distinct jsonb DEFAULT '[]', p_confirmed_links jsonb DEFAULT '{}'
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE
    owner_id uuid := auth.uid();
    parent public.playlists%ROWTYPE;
    previous_result jsonb;
    item jsonb;
    metadata jsonb;
    incoming_track_id text;
    provider_id text;
    canonical_id text;
    occurrence_id text;
    linked_occurrence text;
    next_position integer;
    added_count integer := 0;
    excluded_count integer := 0;
    present_count integer := 0;
    uncertain jsonb := '[]';
    result jsonb;
BEGIN
    SELECT * INTO parent FROM public.playlists
    WHERE id = p_playlist_id AND user_id = owner_id FOR UPDATE;
    IF NOT FOUND THEN RETURN jsonb_build_object('status', 'not_found'); END IF;

    SELECT r.result INTO previous_result FROM public.playlist_refresh_results r
    WHERE r.playlist_id = parent.id AND r.request_id = p_request_id;
    IF FOUND THEN RETURN previous_result; END IF;
    IF parent.source_provider IS NULL OR parent.source_revision IS DISTINCT FROM p_source_revision THEN
        RETURN jsonb_build_object('status', 'conflict');
    END IF;
    IF jsonb_typeof(p_tracks) IS DISTINCT FROM 'array' OR jsonb_array_length(p_tracks) > 10000
       OR jsonb_typeof(p_confirmed_distinct) IS DISTINCT FROM 'array'
       OR jsonb_array_length(p_confirmed_distinct) > 10000
       OR jsonb_typeof(p_confirmed_links) IS DISTINCT FROM 'object' THEN
        RAISE EXCEPTION USING ERRCODE = '22023', MESSAGE = 'Invalid source inventory';
    END IF;

    SELECT COALESCE(max(i.position), -1) + 1 INTO next_position
    FROM public.playlist_items i WHERE i.playlist_id = parent.id;
    FOR item IN SELECT value FROM jsonb_array_elements(p_tracks) LOOP
        incoming_track_id := item->>'track_id';
        provider_id := item->>'provider_id';
        canonical_id := 'trk_' || rtrim(translate(replace(encode(convert_to(
            'v1:track:' || parent.source_provider || ':' || provider_id, 'UTF8'), 'base64'), chr(10), ''), '+/', '-_'), '=');
        IF item->>'provider' IS DISTINCT FROM parent.source_provider
            OR incoming_track_id IS NULL OR incoming_track_id IS DISTINCT FROM canonical_id OR provider_id IS NULL
            OR (parent.source_provider = 'spotify' AND provider_id !~ '^[A-Za-z0-9]{22}$')
            OR (parent.source_provider = 'deezer' AND provider_id !~ '^[1-9][0-9]{0,19}$')
            OR length(btrim(COALESCE(item->>'title', ''))) NOT BETWEEN 1 AND 512
            OR length(btrim(COALESCE(item->>'artist', ''))) NOT BETWEEN 1 AND 512
            OR length(COALESCE(item->>'album', '')) > 512
            OR (item->>'duration_ms' IS NOT NULL AND item->>'duration_ms' !~ '^[0-9]{1,9}$')
            OR (item->>'cover_uri' IS NOT NULL AND (
                length(item->>'cover_uri') > 1200 OR item->>'cover_uri' !~
                    '^https://(i[.]scdn[.]co|cdn-images[.]dzcdn[.]net|e-cdns-images[.]dzcdn[.]net)/[A-Za-z0-9_./?=%&@,+-]+$'
            )) THEN
            RAISE EXCEPTION USING ERRCODE = '22023', MESSAGE = 'Invalid source track';
        END IF;
        metadata := jsonb_strip_nulls(jsonb_build_object(
            'provider', parent.source_provider, 'provider_id', provider_id, 'title', item->>'title',
            'artist', item->>'artist', 'album', item->>'album',
            'duration_ms', (item->>'duration_ms')::integer, 'cover_uri', item->>'cover_uri'
        ));
        IF EXISTS (
            SELECT 1 FROM public.playlist_source_exclusions e WHERE e.playlist_id = parent.id
            AND (e.track_id IN (incoming_track_id, parent.source_provider || ':' || provider_id,
                'track:' || parent.source_provider || ':' || provider_id)
                OR (parent.source_provider = 'deezer' AND e.track_id IN (provider_id, 'trk_deezer_' || provider_id))
                OR (e.metadata->>'provider' = parent.source_provider AND e.metadata->>'provider_id' = provider_id))
        ) THEN
            excluded_count := excluded_count + 1;
            CONTINUE;
        END IF;
        IF EXISTS (
            SELECT 1 FROM public.playlist_items i WHERE i.playlist_id = parent.id
            AND (i.track_id IN (incoming_track_id, parent.source_provider || ':' || provider_id,
                'track:' || parent.source_provider || ':' || provider_id)
                OR (parent.source_provider = 'deezer' AND i.track_id IN (provider_id, 'trk_deezer_' || provider_id))
                OR (i.source_metadata->>'provider' = parent.source_provider AND i.source_metadata->>'provider_id' = provider_id))
        ) THEN
            present_count := present_count + 1;
            CONTINUE;
        END IF;
        linked_occurrence := p_confirmed_links->>incoming_track_id;
        IF linked_occurrence IS NOT NULL THEN
            UPDATE public.playlist_items SET source_metadata = metadata
            WHERE id = linked_occurrence AND playlist_id = parent.id AND source_metadata IS NULL;
            IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE = 'P0409'; END IF;
            present_count := present_count + 1;
            CONTINUE;
        END IF;
        IF ((item->>'requires_confirmation')::boolean IS TRUE OR EXISTS (
            SELECT 1 FROM public.playlist_source_exclusions e WHERE e.playlist_id = parent.id
            AND lower(btrim(e.metadata->>'title')) = lower(btrim(item->>'title'))
            AND lower(btrim(e.metadata->>'artist')) = lower(btrim(item->>'artist'))
        ))
           AND NOT p_confirmed_distinct @> jsonb_build_array(incoming_track_id) THEN
            IF NOT uncertain @> jsonb_build_array(jsonb_build_object('track_id', incoming_track_id)) THEN
                uncertain := uncertain || jsonb_build_array(metadata || jsonb_build_object('track_id', incoming_track_id));
            END IF;
            CONTINUE;
        END IF;

        occurrence_id := 'playlist-item:' || gen_random_uuid()::text;
        INSERT INTO public.playlist_items(id, playlist_id, track_id, position, added_at,
            added_from_context_type, added_from_context_id, source_metadata)
        VALUES(occurrence_id, parent.id, incoming_track_id, next_position, clock_timestamp(),
            'source_refresh', parent.id, metadata);
        PERFORM public.aura_enqueue_playlist_acquisition(owner_id, incoming_track_id, metadata);
        next_position := next_position + 1;
        added_count := added_count + 1;
    END LOOP;
    result := jsonb_build_object('status', 'applied', 'request_id', p_request_id,
        'source_revision', p_source_revision, 'completed_at', clock_timestamp(),
        'added_count', added_count, 'excluded_count', excluded_count, 'present_count', present_count,
        'uncertain', uncertain
    );
    INSERT INTO public.playlist_refresh_results(playlist_id, request_id, source_revision, result)
    VALUES(parent.id, p_request_id, p_source_revision, result);
    UPDATE public.playlists SET updated_at = greatest(clock_timestamp(), parent.updated_at + interval '1 microsecond')
    WHERE id = parent.id AND user_id = owner_id;
    RETURN result;
EXCEPTION WHEN SQLSTATE 'P0409' THEN
    RETURN jsonb_build_object('status', 'conflict');
END
$function$;
REVOKE ALL ON FUNCTION public.aura_commit_playlist_refresh(text,uuid,bigint,jsonb,jsonb,jsonb)
    FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.aura_commit_playlist_refresh(text,uuid,bigint,jsonb,jsonb,jsonb) TO authenticated;

CREATE OR REPLACE FUNCTION public.aura_playlist_snapshot()
RETURNS jsonb
LANGUAGE sql STABLE SECURITY INVOKER SET search_path = ''
AS $function$
    WITH owned_playlists AS (
        SELECT p.id, p.user_id, p.name, p.cover_uri, p.is_pinned, p.created_at, p.updated_at,
               p.source_provider, p.source_external_id, p.source_revision,
               COALESCE(items.entries, '[]'::jsonb) AS items, items.item_count,
               (
                   SELECT refresh.result FROM public.playlist_refresh_results refresh
                   WHERE refresh.playlist_id = p.id AND refresh.source_revision = p.source_revision
                   ORDER BY refresh.completed_at DESC, refresh.request_id DESC LIMIT 1
               ) AS last_refresh
        FROM public.playlists p
        CROSS JOIN LATERAL (
            SELECT jsonb_agg(to_jsonb(i) ORDER BY i.position) AS entries, count(*) AS item_count
            FROM public.playlist_items i WHERE i.playlist_id = p.id
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
