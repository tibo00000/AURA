BEGIN;

INSERT INTO public.profiles(id) VALUES
    ('c0000000-0000-0000-0000-000000000001'),
    ('c0000000-0000-0000-0000-000000000002');
INSERT INTO public.playlists(id, user_id, name) VALUES
    ('fixture-mix', 'c0000000-0000-0000-0000-000000000001', 'Mix'),
    ('fixture-private', 'c0000000-0000-0000-0000-000000000002', 'Private');
INSERT INTO public.playlist_items(id, playlist_id, track_id, position) VALUES
    ('fixture-a1', 'fixture-mix', 'same-recording', 0),
    ('fixture-a2', 'fixture-mix', 'same-recording', 1),
    ('fixture-b', 'fixture-private', 'another-recording', 0);

SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', 'c0000000-0000-0000-0000-000000000001', true);
DO $test$
DECLARE
    snapshot jsonb;
    initial_revision bigint;
    removal_revision bigint;
    outcome jsonb;
BEGIN
    snapshot := public.aura_playlist_snapshot();
    initial_revision := (snapshot->>'revision')::bigint;
    IF initial_revision IS NULL OR initial_revision <= 0
        OR snapshot->>'playlist_count' <> '1' OR snapshot->>'item_count' <> '2'
        OR snapshot #>> '{playlists,0,id}' <> 'fixture-mix'
        OR snapshot #>> '{playlists,0,items,0,id}' <> 'fixture-a1'
        OR snapshot #>> '{playlists,0,items,1,id}' <> 'fixture-a2' THEN
        RAISE EXCEPTION 'Snapshot lost an occurrence or returned another account';
    END IF;
    outcome := public.aura_apply_playlist_operation(
        'remove-1', 'playlist_item', 'fixture-a1', 'delete', '{"playlist_id":"fixture-mix"}'
    );
    removal_revision := (outcome->>'playlist_revision')::bigint;
    snapshot := public.aura_playlist_snapshot();
    IF removal_revision IS NULL OR removal_revision <= initial_revision
        OR (snapshot->>'revision')::bigint <> removal_revision OR snapshot->>'item_count' <> '1'
        OR snapshot #>> '{playlists,0,items,0,id}' <> 'fixture-a2' THEN
        RAISE EXCEPTION 'Expected a single remaining occurrence';
    END IF;
    outcome := public.aura_apply_playlist_operation(
        'remove-1', 'playlist_item', 'fixture-a2', 'delete', '{"playlist_id":"fixture-mix"}'
    );
    IF outcome->>'status' <> 'ignored_duplicate' OR (outcome->>'playlist_revision')::bigint <> removal_revision THEN
        RAISE EXCEPTION 'Lost acknowledgement was not treated as replay';
    END IF;
    PERFORM public.aura_apply_playlist_operation(
        'append-1', 'playlist_item', 'fixture-a3', 'create', '{"playlist_id":"fixture-mix","track_id":"new-track"}'
    );
    PERFORM public.aura_apply_playlist_operation(
        'append-2', 'playlist_item', 'fixture-a4', 'create', '{"playlist_id":"fixture-mix","track_id":"same-recording"}'
    );
    IF public.aura_apply_playlist_operation(
        'move-1', 'playlist_reorder', 'fixture-mix', 'update',
        '{"item_id":"fixture-a4","anchor_id":"fixture-a2","side":"before"}'
    )->>'status' <> 'applied' THEN
        RAISE EXCEPTION 'Concurrent append prevented anchored move';
    END IF;
    snapshot := public.aura_playlist_snapshot();
    IF snapshot #>> '{playlists,0,items,0,id}' <> 'fixture-a4'
        OR snapshot #>> '{playlists,0,items,1,id}' <> 'fixture-a2'
        OR snapshot #>> '{playlists,0,items,2,id}' <> 'fixture-a3' THEN
        RAISE EXCEPTION 'Anchor move lost an occurrence or reordered unrelated items';
    END IF;
    outcome := public.aura_apply_playlist_operation(
        'old-reorder', 'playlist_reorder', 'fixture-mix', 'update',
        jsonb_build_object('base_order_token', 'ord_' || left(md5(snapshot #>> '{playlists,0,updated_at}'), 8),
            'items', '[{"playlist_item_id":"fixture-a4","position":1},{"playlist_item_id":"fixture-a2","position":0}]'::jsonb)
    );
    IF outcome->>'status' <> 'applied'
        OR public.aura_playlist_snapshot() #>> '{playlists,0,items,0,id}' <> 'fixture-a2' THEN
        RAISE EXCEPTION 'Queued reorder from a previous Android version was not preserved';
    END IF;
    IF public.aura_apply_playlist_operation(
        'move-failed', 'playlist_reorder', 'fixture-mix', 'update',
        '{"item_id":"fixture-a4","anchor_id":"fixture-a1","side":"before"}'
    )->>'status' <> 'conflict' OR EXISTS (
        SELECT 1 FROM public.processed_operations WHERE operation_id = 'move-failed'
    ) THEN
        RAISE EXCEPTION 'An unresolved operation was acknowledged';
    END IF;
    DELETE FROM public.playlists WHERE id = 'fixture-mix';
    IF (public.aura_playlist_snapshot()->>'revision')::bigint <= (snapshot->>'revision')::bigint
       OR public.aura_playlist_snapshot()->>'playlist_count' <> '0' THEN
        RAISE EXCEPTION 'Legacy delete did not advance the revision of an empty inventory';
    END IF;
END
$test$;

SELECT set_config('request.jwt.claim.sub', 'c0000000-0000-0000-0000-000000000002', true);
DO $test$
BEGIN
    IF public.aura_playlist_snapshot() #>> '{playlists,0,items,0,id}' <> 'fixture-b' THEN
        RAISE EXCEPTION 'Second account did not receive its own playlist';
    END IF;
END
$test$;

RESET ROLE;
DO $test$
BEGIN
    IF has_function_privilege('anon', 'public.aura_playlist_snapshot()', 'EXECUTE') THEN
        RAISE EXCEPTION 'Anonymous execution is enabled';
    END IF;
END
$test$;
ROLLBACK;
