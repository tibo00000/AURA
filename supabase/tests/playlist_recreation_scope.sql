BEGIN;

INSERT INTO public.profiles(id) VALUES
    ('c0000000-0000-0000-0000-000000000081'),
    ('c0000000-0000-0000-0000-000000000082');
INSERT INTO public.playlists(id, user_id, name, source_provider, source_external_id) VALUES
    ('scope-batch', 'c0000000-0000-0000-0000-000000000081', 'Recreate from batch', 'deezer', '123'),
    ('scope-rest', 'c0000000-0000-0000-0000-000000000081', 'Recreate from REST', 'deezer', '123'),
    ('scope-survivor', 'c0000000-0000-0000-0000-000000000081', 'Keep this playlist', 'deezer', '123'),
    ('scope-other', 'c0000000-0000-0000-0000-000000000082', 'Other account', 'deezer', '123');
INSERT INTO public.playlist_items(id, playlist_id, track_id, position, source_metadata) VALUES
    ('scope-batch-entry', 'scope-batch', 'trk_scope_shared', 0, NULL),
    ('scope-rest-entry', 'scope-rest', 'trk_scope_shared', 0, NULL),
    ('scope-keep-first', 'scope-survivor', 'trk_scope_shared', 0, NULL),
    ('scope-keep-repeat', 'scope-survivor', 'trk_scope_shared', 1, NULL),
    ('scope-other-entry', 'scope-other', 'trk_scope_shared', 0, NULL);
INSERT INTO public.playlist_source_exclusions(playlist_id, track_id) VALUES
    ('scope-batch', 'removed-before-recreation'),
    ('scope-rest', 'removed-before-recreation'),
    ('scope-survivor', 'keep-this-exclusion');
INSERT INTO public.download_jobs(id, user_id, track_id, provider_name, status, playlist_acquisition) VALUES
    ('job_scope_shared', 'c0000000-0000-0000-0000-000000000081', 'trk_scope_shared', 'youtube', 'queued', true);
INSERT INTO public.playlist_acquisition_intents(user_id, track_id, metadata, status, reserved_job_id, job_id) VALUES
    ('c0000000-0000-0000-0000-000000000081', 'trk_scope_shared',
     '{"title":"Shared recording","artist":"Fixture"}', 'admitted', 'job_scope_shared', 'job_scope_shared');

CREATE TEMP TABLE scope_before AS SELECT
    (SELECT jsonb_agg(to_jsonb(p) ORDER BY id) FROM public.playlists p
     WHERE id IN ('scope-survivor', 'scope-other')) AS playlists,
    (SELECT jsonb_agg(to_jsonb(i) ORDER BY id) FROM public.playlist_items i
     WHERE playlist_id IN ('scope-survivor', 'scope-other')) AS items,
    (SELECT to_jsonb(j) FROM public.download_jobs j WHERE id = 'job_scope_shared') AS job,
    (SELECT to_jsonb(i) FROM public.playlist_acquisition_intents i
     WHERE user_id = 'c0000000-0000-0000-0000-000000000081' AND track_id = 'trk_scope_shared') AS intent;

SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', 'c0000000-0000-0000-0000-000000000081', true);
DO $test$
DECLARE
    result jsonb;
BEGIN
    result := public.aura_apply_playlist_operation('scope-delete', 'playlist', 'scope-batch', 'delete', '{}');
    IF result->>'status' IS DISTINCT FROM 'applied' THEN
        RAISE EXCEPTION 'Playlist batch deletion failed';
    END IF;
    DELETE FROM public.playlists WHERE id = 'scope-rest';
    IF NOT FOUND THEN RAISE EXCEPTION 'Playlist REST deletion failed'; END IF;

    result := public.aura_apply_playlist_operation('scope-recreate', 'playlist', 'scope-recreated', 'create',
        '{"name":"Recreated playlist"}');
    IF result->>'status' IS DISTINCT FROM 'applied' THEN
        RAISE EXCEPTION 'Playlist recreation failed';
    END IF;
    PERFORM public.aura_set_playlist_source('scope-recreated', 'deezer', '123', 0);
    PERFORM public.aura_apply_playlist_operation('scope-readd', 'playlist_item', 'scope-new-entry', 'create',
        '{"playlist_id":"scope-recreated","track_id":"trk_scope_shared"}');
END
$test$;
RESET ROLE;

DO $test$
DECLARE
    before scope_before%ROWTYPE;
BEGIN
    SELECT * INTO before FROM scope_before;
    IF (SELECT jsonb_agg(to_jsonb(p) ORDER BY id) FROM public.playlists p
        WHERE id IN ('scope-survivor', 'scope-other')) IS DISTINCT FROM before.playlists
        OR (SELECT jsonb_agg(to_jsonb(i) ORDER BY id) FROM public.playlist_items i
            WHERE playlist_id IN ('scope-survivor', 'scope-other')) IS DISTINCT FROM before.items THEN
        RAISE EXCEPTION 'Recreation changed another playlist or its repeated occurrences';
    END IF;
    IF (SELECT to_jsonb(j) FROM public.download_jobs j WHERE id = 'job_scope_shared') IS DISTINCT FROM before.job
        OR (SELECT to_jsonb(i) FROM public.playlist_acquisition_intents i
            WHERE user_id = 'c0000000-0000-0000-0000-000000000081' AND track_id = 'trk_scope_shared') IS DISTINCT FROM before.intent THEN
        RAISE EXCEPTION 'Recreation changed the shared audio job or intention';
    END IF;
    IF EXISTS (SELECT 1 FROM public.playlist_source_exclusions WHERE playlist_id IN ('scope-batch', 'scope-rest'))
        OR NOT EXISTS (SELECT 1 FROM public.playlist_source_exclusions
                       WHERE playlist_id = 'scope-survivor' AND track_id = 'keep-this-exclusion') THEN
        RAISE EXCEPTION 'Exclusion cleanup crossed playlist boundaries';
    END IF;
    IF EXISTS (SELECT 1 FROM public.playlist_items WHERE playlist_id IN ('scope-batch', 'scope-rest'))
        OR NOT EXISTS (SELECT 1 FROM public.playlist_items WHERE id = 'scope-new-entry') THEN
        RAISE EXCEPTION 'Deleted or recreated playlist entries are inconsistent';
    END IF;
END
$test$;

ROLLBACK;
