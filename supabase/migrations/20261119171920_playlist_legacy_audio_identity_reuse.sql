-- Eleven existing personal audio IDs use the historical track_ prefix.
-- Only the new reuse-format validation changes; ownership, exclusions and jobs remain scoped.
BEGIN;
DO $precondition$
BEGIN
 IF (SELECT md5(replace(prosrc,E'\r\n',E'\n')) FROM pg_proc
     WHERE oid='public.aura_commit_playlist_refresh(text,uuid,bigint,jsonb,jsonb,jsonb)'::regprocedure)
     NOT IN ('3fb76a880cbc47c1c3e8d5c7e43dc9b5','bb5160ce4e765f8c6d72a420cece9a22') THEN
   RAISE EXCEPTION 'Playlist refresh definition changed; review before replacing';
 END IF;
END
$precondition$;

CREATE OR REPLACE FUNCTION public.aura_commit_playlist_refresh(
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
    linked_item public.playlist_items%ROWTYPE;
    source_item public.playlist_items%ROWTYPE;
    target_track_id text;
    credit jsonb;
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
        IF jsonb_typeof(COALESCE(item->'artists','[]'::jsonb)) IS DISTINCT FROM 'array'
           OR jsonb_array_length(COALESCE(item->'artists','[]'::jsonb)) > 32 THEN
            RAISE EXCEPTION USING ERRCODE='22023',MESSAGE='Invalid artist credits';
        END IF;
        FOR credit IN SELECT value FROM jsonb_array_elements(COALESCE(item->'artists','[]'::jsonb)) LOOP
            IF credit->>'provider' IS DISTINCT FROM parent.source_provider
               OR length(btrim(COALESCE(credit->>'name',''))) NOT BETWEEN 1 AND 512
               OR (parent.source_provider='spotify' AND COALESCE(credit->>'provider_id','') !~ '^[A-Za-z0-9]{22}$')
               OR (parent.source_provider='deezer' AND COALESCE(credit->>'provider_id','') !~ '^[1-9][0-9]{0,19}$')
               OR credit->>'id' IS DISTINCT FROM 'art_' || rtrim(translate(replace(encode(convert_to(
                    'v1:artist:' || parent.source_provider || ':' || (credit->>'provider_id'),'UTF8'),'base64'),chr(10),''),'+/','-_'),'=')
               OR COALESCE(credit->>'role','performer') NOT IN ('primary','performer','featured') THEN
                RAISE EXCEPTION USING ERRCODE='22023',MESSAGE='Invalid artist identity';
            END IF;
        END LOOP;
        metadata := jsonb_strip_nulls(jsonb_build_object(
            'provider', parent.source_provider, 'provider_id', provider_id, 'title', item->>'title',
            'artist', item->>'artist', 'album', item->>'album',
            'duration_ms', (item->>'duration_ms')::integer, 'cover_uri', item->>'cover_uri',
            'artists', COALESCE(item->'artists','[]'::jsonb),
            'identity_decision', CASE WHEN p_confirmed_distinct ? incoming_track_id THEN 'distinct' ELSE NULL END
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
        SELECT * INTO source_item FROM public.playlist_items i
        WHERE i.playlist_id=parent.id AND (i.track_id IN (incoming_track_id, parent.source_provider || ':' || provider_id,
                'track:' || parent.source_provider || ':' || provider_id)
                OR (parent.source_provider = 'deezer' AND i.track_id IN (provider_id, 'trk_deezer_' || provider_id))
                OR (i.source_metadata->>'provider' = parent.source_provider AND i.source_metadata->>'provider_id' = provider_id)) ORDER BY i.position,i.id LIMIT 1 FOR UPDATE;
        IF FOUND THEN
            linked_occurrence := p_confirmed_links->>incoming_track_id;
            IF linked_occurrence IS NOT NULL THEN
                IF linked_occurrence LIKE 'aura-track-link:%' THEN
                    target_track_id := substring(linked_occurrence FROM length('aura-track-link:')+1);
                    IF length(target_track_id) NOT BETWEEN 5 AND 250 OR target_track_id !~ '^(trk_|track[:_])' THEN
                        RAISE EXCEPTION USING ERRCODE='P0409';
                    END IF;
                ELSE
                    SELECT * INTO linked_item FROM public.playlist_items WHERE id=linked_occurrence AND playlist_id=parent.id FOR UPDATE;
                    IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0409'; END IF;
                    target_track_id := linked_item.track_id;
                END IF;
                -- Same occurrence IDs and positions; only the explicitly confirmed audio pointer changes.
                UPDATE public.playlist_items i SET track_id=target_track_id,
                    source_metadata=COALESCE(i.source_metadata,metadata)
                WHERE i.playlist_id=parent.id AND (i.track_id IN (incoming_track_id, parent.source_provider || ':' || provider_id,
                'track:' || parent.source_provider || ':' || provider_id)
                OR (parent.source_provider = 'deezer' AND i.track_id IN (provider_id, 'trk_deezer_' || provider_id))
                OR (i.source_metadata->>'provider' = parent.source_provider AND i.source_metadata->>'provider_id' = provider_id));
            ELSIF COALESCE((item->>'requires_confirmation')::boolean,false)
                  AND source_item.track_id IN (incoming_track_id,parent.source_provider || ':' || provider_id,
                    'track:' || parent.source_provider || ':' || provider_id,'trk_' || parent.source_provider || '_' || provider_id,
                    CASE WHEN parent.source_provider='deezer' THEN provider_id ELSE incoming_track_id END)
                  AND COALESCE(source_item.source_metadata->>'identity_decision','')<>'distinct'
                  AND NOT p_confirmed_distinct ? incoming_track_id THEN
                IF NOT uncertain @> jsonb_build_array(jsonb_build_object('track_id',incoming_track_id)) THEN
                    uncertain := uncertain || jsonb_build_array(metadata || jsonb_build_object('track_id',incoming_track_id));
                END IF;
            END IF;
            IF jsonb_array_length(metadata->'artists') > 0 THEN
                UPDATE public.playlist_items i SET source_metadata=COALESCE(i.source_metadata,metadata) ||
                    jsonb_build_object('artists',metadata->'artists')
                WHERE i.playlist_id=parent.id AND (i.track_id IN (incoming_track_id, parent.source_provider || ':' || provider_id,
                'track:' || parent.source_provider || ':' || provider_id)
                OR (parent.source_provider = 'deezer' AND i.track_id IN (provider_id, 'trk_deezer_' || provider_id))
                OR (i.source_metadata->>'provider' = parent.source_provider AND i.source_metadata->>'provider_id' = provider_id));
            END IF;
            IF p_confirmed_distinct ? incoming_track_id THEN
                UPDATE public.playlist_items i SET source_metadata=COALESCE(i.source_metadata,metadata) ||
                    jsonb_build_object('identity_decision','distinct')
                WHERE i.playlist_id=parent.id AND (i.track_id IN (incoming_track_id, parent.source_provider || ':' || provider_id,
                'track:' || parent.source_provider || ':' || provider_id)
                OR (parent.source_provider = 'deezer' AND i.track_id IN (provider_id, 'trk_deezer_' || provider_id))
                OR (i.source_metadata->>'provider' = parent.source_provider AND i.source_metadata->>'provider_id' = provider_id));
            END IF;
            UPDATE public.playlist_acquisition_intents SET metadata=playlist_acquisition_intents.metadata ||
                CASE WHEN jsonb_array_length(COALESCE(item->'artists','[]'::jsonb))>0
                    THEN jsonb_build_object('artists',item->'artists') ELSE '{}'::jsonb END ||
                CASE WHEN p_confirmed_distinct ? incoming_track_id THEN jsonb_build_object('identity_decision','distinct') ELSE '{}'::jsonb END
            WHERE user_id=owner_id AND track_id=source_item.track_id
              AND (lease_until IS NULL OR lease_until<=clock_timestamp());
            IF p_confirmed_distinct ? incoming_track_id THEN
                UPDATE public.playlist_acquisition_intents SET status='pending',error_code='retry_requested',next_attempt_at=clock_timestamp()
                WHERE user_id=owner_id AND track_id=source_item.track_id AND status IN ('source_not_authorized','cancelled','failed')
                    AND (lease_until IS NULL OR lease_until<=clock_timestamp());
            END IF;
            present_count := present_count+1;
            CONTINUE;
        END IF;
        linked_occurrence := p_confirmed_links->>incoming_track_id;
        IF linked_occurrence LIKE 'aura-track-link:%' THEN
            target_track_id := substring(linked_occurrence FROM length('aura-track-link:')+1);
            IF length(target_track_id) NOT BETWEEN 5 AND 250 OR target_track_id !~ '^(trk_|track[:_])' THEN
                RAISE EXCEPTION USING ERRCODE='P0409';
            END IF;
            INSERT INTO public.playlist_items(id,playlist_id,track_id,position,added_at,
                added_from_context_type,added_from_context_id,source_metadata)
            VALUES('playlist-item:' || gen_random_uuid()::text,parent.id,target_track_id,next_position,
                clock_timestamp(),'source_refresh',parent.id,metadata);
            next_position := next_position+1;
            present_count := present_count+1;
            CONTINUE;
        END IF;
        IF linked_occurrence IS NOT NULL THEN
            SELECT * INTO linked_item FROM public.playlist_items
            WHERE id = linked_occurrence AND playlist_id = parent.id FOR UPDATE;
            IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE = 'P0409'; END IF;
            IF linked_item.source_metadata IS NULL THEN
                UPDATE public.playlist_items SET source_metadata = metadata WHERE id = linked_item.id;
                present_count := present_count + 1;
            ELSE
                -- Another catalogue edition already owns this occurrence. The user's
                -- explicit reuse confirms the audio, never replacement of that link.
                INSERT INTO public.playlist_items(id, playlist_id, track_id, position, added_at,
                    added_from_context_type, added_from_context_id, source_metadata)
                VALUES ('playlist-item:' || gen_random_uuid()::text, parent.id, linked_item.track_id,
                    next_position, clock_timestamp(), 'source_confirmation', parent.id, metadata);
                next_position := next_position + 1;
                added_count := added_count + 1;
            END IF;
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
    RETURN jsonb_build_object('status', 'conflict', 'reason', 'playlist_link_changed');
END
$function$;
REVOKE ALL ON FUNCTION public.aura_commit_playlist_refresh(text,uuid,bigint,jsonb,jsonb,jsonb)
    FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.aura_commit_playlist_refresh(text,uuid,bigint,jsonb,jsonb,jsonb) TO authenticated;


COMMIT;
