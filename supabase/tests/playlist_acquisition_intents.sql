BEGIN;

INSERT INTO public.profiles(id) VALUES
    ('c0000000-0000-0000-0000-000000000021'),
    ('c0000000-0000-0000-0000-000000000022');
INSERT INTO public.playlists(id, user_id, name) VALUES
    ('acquisition-fixture', 'c0000000-0000-0000-0000-000000000021', 'Existing');

DO $test$
DECLARE
    account_id uuid := 'c0000000-0000-0000-0000-000000000021';
BEGIN
    BEGIN
        INSERT INTO public.playlist_items(id, playlist_id, track_id, position)
        VALUES ('broken-atomic', 'acquisition-fixture', 'trk_recording', 0);
        PERFORM public.aura_enqueue_playlist_acquisition(account_id, 'trk_recording', '{"title":"Music"}');
        RAISE EXCEPTION USING ERRCODE = 'P0901';
    EXCEPTION WHEN SQLSTATE 'P0901' THEN NULL;
    END;
    IF EXISTS (SELECT 1 FROM public.playlist_items WHERE id = 'broken-atomic') OR
       EXISTS (SELECT 1 FROM public.playlist_acquisition_intents WHERE user_id = account_id) THEN
        RAISE EXCEPTION 'Rolling back leaked an entry or an intention';
    END IF;

    INSERT INTO public.playlist_items(id, playlist_id, track_id, position)
    VALUES ('correct-atomic', 'acquisition-fixture', 'trk_recording', 0);
    PERFORM public.aura_enqueue_playlist_acquisition(account_id, 'trk_recording', '{"title":"Music"}');
    PERFORM public.aura_enqueue_playlist_acquisition(account_id, 'trk_recording', '{"title":"Another attempt"}');
    PERFORM public.aura_enqueue_playlist_acquisition(
        'c0000000-0000-0000-0000-000000000022', 'trk_recording', '{"title":"Music"}'
    );
    IF (SELECT count(*) FROM public.playlist_acquisition_intents WHERE track_id = 'trk_recording') <> 2
        OR (SELECT metadata->>'title' FROM public.playlist_acquisition_intents
            WHERE user_id = account_id AND track_id = 'trk_recording') IS DISTINCT FROM 'Music'
        OR EXISTS (SELECT 1 FROM public.download_jobs WHERE track_id = 'trk_recording') THEN
        RAISE EXCEPTION 'Duplicate admission, changed payload or unrequested job';
    END IF;

    IF has_table_privilege('authenticated', 'public.playlist_acquisition_intents', 'SELECT,INSERT,UPDATE')
        OR has_function_privilege('authenticated', 'public.aura_enqueue_playlist_acquisition(uuid,text,jsonb)', 'EXECUTE')
        OR has_table_privilege('anon', 'public.playlist_acquisition_intents', 'SELECT,INSERT,UPDATE') THEN
        RAISE EXCEPTION 'Client may access the backend queue';
    END IF;
END
$test$;
ROLLBACK;
