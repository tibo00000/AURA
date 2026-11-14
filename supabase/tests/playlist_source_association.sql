BEGIN;

INSERT INTO public.profiles(id) VALUES
    ('c0000000-0000-0000-0000-000000000011'),
    ('c0000000-0000-0000-0000-000000000012');
INSERT INTO public.playlists(id, user_id, name) VALUES
    ('source-fixture', 'c0000000-0000-0000-0000-000000000011', 'Existing');
INSERT INTO public.playlist_items(id, playlist_id, track_id, position) VALUES
    ('source-original-1', 'source-fixture', 'same-track', 0),
    ('source-original-2', 'source-fixture', 'same-track', 1);

SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', 'c0000000-0000-0000-0000-000000000011', true);
DO $test$
DECLARE
    before_snapshot jsonb := public.aura_playlist_snapshot();
    after_snapshot jsonb;
    outcome jsonb;
BEGIN
    outcome := public.aura_set_playlist_source('source-fixture', 'spotify', '0123456789ABCDEFGHIJKL', 0);
    IF outcome->>'status' IS DISTINCT FROM 'applied' OR outcome->>'source_revision' IS DISTINCT FROM '1' THEN
        RAISE EXCEPTION 'Failed to associate an existing playlist';
    END IF;
    after_snapshot := public.aura_playlist_snapshot();
    IF (after_snapshot->>'revision')::bigint <= (before_snapshot->>'revision')::bigint
        OR after_snapshot #>> '{playlists,0,source_provider}' IS DISTINCT FROM 'spotify'
        OR after_snapshot #> '{playlists,0,items}' IS DISTINCT FROM before_snapshot #> '{playlists,0,items}' THEN
        RAISE EXCEPTION 'Snapshot missed the association or changed the occurrences';
    END IF;
    IF public.aura_set_playlist_source('source-fixture', 'spotify', '0123456789ABCDEFGHIJKL', 0) <> outcome
        OR public.aura_set_playlist_source('source-fixture', 'deezer', '123', 0)->>'status' IS DISTINCT FROM 'conflict'
        OR public.aura_playlist_snapshot()->>'revision' IS DISTINCT FROM after_snapshot->>'revision' THEN
        RAISE EXCEPTION 'Replay or conflict changed the saved association';
    END IF;
    outcome := public.aura_set_playlist_source('source-fixture', NULL, NULL, 1);
    IF outcome->>'source_revision' IS DISTINCT FROM '2'
        OR public.aura_playlist_snapshot() #>> '{playlists,0,source_provider}' IS NOT NULL THEN
        RAISE EXCEPTION 'Removing a source lost its revision';
    END IF;
END
$test$;

SELECT set_config('request.jwt.claim.sub', 'c0000000-0000-0000-0000-000000000012', true);
DO $test$
BEGIN
    IF public.aura_set_playlist_source('source-fixture', 'deezer', '123', 2)->>'status' IS DISTINCT FROM 'not_found'
        OR public.aura_playlist_snapshot()->>'playlist_count' IS DISTINCT FROM '0' THEN
        RAISE EXCEPTION 'Another account could read or mutate the association';
    END IF;
    IF has_function_privilege('anon', 'public.aura_set_playlist_source(text,text,text,bigint)', 'EXECUTE') THEN
        RAISE EXCEPTION 'Anonymous execution is enabled';
    END IF;
END
$test$;
ROLLBACK;
