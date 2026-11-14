BEGIN;

INSERT INTO public.profiles(id) VALUES
    ('c0000000-0000-0000-0000-000000000031'),
    ('c0000000-0000-0000-0000-000000000032');
INSERT INTO public.playlists(id,user_id,name,source_provider,source_external_id)
VALUES ('refresh-fixture','c0000000-0000-0000-0000-000000000031','Mix','deezer','123');
INSERT INTO public.playlist_items(id,playlist_id,track_id,position) VALUES
    ('refresh-old-1','refresh-fixture','local-track',1),
    ('refresh-old-2','refresh-fixture','local-track',4);

SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', 'c0000000-0000-0000-0000-000000000031', true);
DO $test$
DECLARE
    recording text := 'trk_' || rtrim(encode(convert_to('v1:track:deezer:456', 'UTF8'), 'base64'), '=');
    tracks jsonb := jsonb_build_array(jsonb_build_object(
        'track_id', recording, 'provider', 'deezer', 'provider_id', '456', 'title', 'A song',
        'artist', 'An artist', 'album', 'The album', 'duration_ms', 234000, 'requires_confirmation', true
    ));
    result jsonb;
    old_items jsonb := public.aura_playlist_snapshot() #> '{playlists,0,items}';
    actual_id text;
BEGIN
    result := public.aura_commit_playlist_refresh('refresh-fixture', '00000000-0000-0000-0000-000000000031', 1, tracks);
    IF result->>'added_count' IS DISTINCT FROM '0' OR jsonb_array_length(result->'uncertain') <> 1
       OR public.aura_playlist_snapshot() #> '{playlists,0,items}' IS DISTINCT FROM old_items THEN
        RAISE EXCEPTION 'An ambiguous candidate was added';
    END IF;
    result := public.aura_commit_playlist_refresh('refresh-fixture', '00000000-0000-0000-0000-000000000032', 1, tracks, jsonb_build_array(recording));
    IF result->>'added_count' IS DISTINCT FROM '1'
       OR public.aura_playlist_snapshot() #>> '{playlists,0,items,2,source_metadata,album}' IS DISTINCT FROM 'The album'
       OR public.aura_playlist_snapshot() #>> '{playlists,0,items,2,position}' IS DISTINCT FROM '5' THEN
        RAISE EXCEPTION 'Confirmed addition lost metadata or rewrote existing positions';
    END IF;
    IF public.aura_commit_playlist_refresh('refresh-fixture', '00000000-0000-0000-0000-000000000032', 1, tracks) IS DISTINCT FROM result THEN
        RAISE EXCEPTION 'A replay returned a different outcome';
    END IF;
    actual_id := public.aura_playlist_snapshot() #>> '{playlists,0,items,2,id}';
    PERFORM public.aura_apply_playlist_operation('actual-remove', 'playlist_item', actual_id, 'delete', '{"playlist_id":"refresh-fixture"}');
    result := public.aura_commit_playlist_refresh('refresh-fixture', '00000000-0000-0000-0000-000000000033', 1, tracks, jsonb_build_array(recording));
    IF result->>'excluded_count' IS DISTINCT FROM '1' OR public.aura_playlist_snapshot() #> '{playlists,0,items}' IS DISTINCT FROM old_items THEN
        RAISE EXCEPTION 'Automatic refresh reintroduced an excluded title';
    END IF;
    PERFORM public.aura_apply_playlist_operation('manual-add', 'playlist_item', 'new-manual-entry', 'create',
        jsonb_build_object('playlist_id', 'refresh-fixture', 'track_id', recording));
    IF public.aura_playlist_snapshot() #>> '{playlists,0,items,2,id}' IS DISTINCT FROM 'new-manual-entry' THEN
        RAISE EXCEPTION 'Manual addition of excluded title failed';
    END IF;
    recording := 'trk_' || rtrim(encode(convert_to('v1:track:deezer:789', 'UTF8'), 'base64'), '=');
    tracks := jsonb_build_array(jsonb_build_object(
        'track_id', recording, 'provider', 'deezer', 'provider_id', '789', 'title', 'Local song',
        'artist', 'An artist', 'requires_confirmation', true
    ));
    result := public.aura_commit_playlist_refresh('refresh-fixture', '00000000-0000-0000-0000-000000000035',
        1, tracks, '[]', jsonb_build_object(recording, 'refresh-old-2'));
    IF result->>'added_count' IS DISTINCT FROM '0'
        OR public.aura_playlist_snapshot() #>> '{playlists,0,items,1,track_id}' IS DISTINCT FROM 'local-track'
        OR public.aura_playlist_snapshot() #>> '{playlists,0,items,1,source_metadata,provider_id}' IS DISTINCT FROM '789' THEN
        RAISE EXCEPTION 'Confirmation did not preserve the original ID and record its provider identity';
    END IF;
    PERFORM public.aura_apply_playlist_operation('alias-remove', 'playlist_item', 'refresh-old-2', 'delete', '{"playlist_id":"refresh-fixture"}');
    result := public.aura_commit_playlist_refresh('refresh-fixture', '00000000-0000-0000-0000-000000000036',
        1, tracks, jsonb_build_array(recording));
    IF result->>'excluded_count' IS DISTINCT FROM '1' THEN
        RAISE EXCEPTION 'Manual removal lost the confirmed identity';
    END IF;
    recording := 'trk_' || rtrim(encode(convert_to('v1:track:deezer:901', 'UTF8'), 'base64'), '=');
    tracks := jsonb_build_array(
        jsonb_build_object('track_id', recording, 'provider', 'deezer', 'provider_id', '901', 'title', 'New title', 'artist', 'Artist'),
        jsonb_build_object('track_id', 'trk_' || rtrim(encode(convert_to('v1:track:deezer:902', 'UTF8'), 'base64'), '='),
            'provider', 'deezer', 'provider_id', '902', 'title', 'Missing occurrence', 'artist', 'Artist')
    );
    old_items := public.aura_playlist_snapshot() #> '{playlists,0,items}';
    result := public.aura_commit_playlist_refresh('refresh-fixture', '00000000-0000-0000-0000-000000000037',
        1, tracks, '[]', jsonb_build_object(tracks #>> '{1,track_id}', 'missing-occurrence'));
    IF result->>'status' IS DISTINCT FROM 'conflict'
        OR public.aura_playlist_snapshot() #> '{playlists,0,items}' IS DISTINCT FROM old_items THEN
        RAISE EXCEPTION 'A stale confirmation partially applied a refresh';
    END IF;
    PERFORM public.aura_set_playlist_source('refresh-fixture', 'deezer', '124', 1);
    result := public.aura_commit_playlist_refresh('refresh-fixture', '00000000-0000-0000-0000-000000000034', 1, tracks);
    IF result->>'status' IS DISTINCT FROM 'conflict' OR public.aura_playlist_snapshot() #>> '{playlists,0,last_refresh}' IS NOT NULL THEN
        RAISE EXCEPTION 'An obsolete source could commit';
    END IF;
END
$test$;

SELECT set_config('request.jwt.claim.sub', 'c0000000-0000-0000-0000-000000000032', true);
DO $test$
BEGIN
    IF public.aura_commit_playlist_refresh('refresh-fixture', '00000000-0000-0000-0000-000000000039', 1, '[]')->>'status' IS DISTINCT FROM 'not_found' THEN
        RAISE EXCEPTION 'Refresh accepted across accounts';
    END IF;
END
$test$;
RESET ROLE;

DO $test$
BEGIN
    IF (SELECT count(*) FROM public.playlist_acquisition_intents WHERE user_id = 'c0000000-0000-0000-0000-000000000031') <> 1 THEN
        RAISE EXCEPTION 'Missing or duplicated transactional intention';
    END IF;
END
$test$;
ROLLBACK;
