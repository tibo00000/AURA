BEGIN;

INSERT INTO public.profiles(id) VALUES
    ('c0000000-0000-0000-0000-000000000071'), ('c0000000-0000-0000-0000-000000000072');
INSERT INTO public.playlists(id, user_id, name, source_provider, source_external_id)
VALUES ('linked-audio-fixture', 'c0000000-0000-0000-0000-000000000071', 'Existing mix', 'deezer', '121');
INSERT INTO public.playlist_items(id, playlist_id, track_id, position, source_metadata) VALUES
    ('local-link', 'linked-audio-fixture', 'track:local:10', 0, NULL),
    ('local-repeat', 'linked-audio-fixture', 'track:local:10', 1, NULL),
    ('conflicting-1', 'linked-audio-fixture', 'track:local:20', 2, '{"provider":"deezer","provider_id":"454"}'),
    ('conflicting-2', 'linked-audio-fixture', 'track:local:20', 3, '{"provider":"deezer","provider_id":"455"}'),
    ('local-upload', 'linked-audio-fixture', 'track:local:30', 4,
        '{"provider":"spotify","provider_id":"0123456789ABCDEFGHIJKL"}');

SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', 'c0000000-0000-0000-0000-000000000071', true);
DO $test$
DECLARE
    canonical_id text := 'trk_' || rtrim(encode(convert_to('v1:track:deezer:123', 'UTF8'), 'base64'), '=');
    uploaded_id text := 'trk_' || rtrim(encode(convert_to('v1:track:spotify:0123456789ABCDEFGHIJKL', 'UTF8'), 'base64'), '=');
    item jsonb := jsonb_build_object('track_id', canonical_id, 'provider', 'deezer', 'provider_id', '123',
        'title', 'Title', 'artist', 'Artist');
    rows jsonb;
BEGIN
    PERFORM public.aura_commit_playlist_refresh('linked-audio-fixture', '00000000-0000-0000-0000-000000000071', 1,
        jsonb_build_array(item), '[]', jsonb_build_object(canonical_id, 'local-link'));
    PERFORM public.aura_apply_playlist_operation('catalog-manual-entry', 'playlist_item', 'catalog-entry', 'create',
        jsonb_build_object('playlist_id', 'linked-audio-fixture', 'track_id', canonical_id));
    rows := public.aura_get_playlist_acquisitions('linked-audio-fixture', true, NULL, ARRAY['track:local:30']);
    IF NOT (rows @> jsonb_build_array(jsonb_build_object('track_id', 'track:local:10', 'audio_track_id', canonical_id, 'status', 'pending')))
        OR NOT (rows @> '[{"track_id":"track:local:20","error_code":"source_identity_ambiguous"}]')
        OR NOT (rows @> jsonb_build_array(jsonb_build_object('track_id', 'track:local:30', 'audio_track_id', uploaded_id,
            'status', 'not_requested'))) THEN
        RAISE EXCEPTION 'Acquisition did not preserve local identity, ambiguity or private availability';
    END IF;
    IF NOT (public.aura_playlist_snapshot() #> '{playlists,0,items}' @>
        '[{"id":"local-link","track_id":"track:local:10","position":0},{"id":"local-repeat","track_id":"track:local:10","position":1}]') THEN
        RAISE EXCEPTION 'Acquisition rewrote existing occurrences';
    END IF;
END
$test$;
SELECT set_config('request.jwt.claim.sub', 'c0000000-0000-0000-0000-000000000072', true);
DO $test$
BEGIN
    IF public.aura_get_playlist_acquisitions('linked-audio-fixture', true) IS NOT NULL
        OR has_function_privilege(current_user, 'public.aura_playlist_acquisition_tracks(text)', 'EXECUTE') THEN
        RAISE EXCEPTION 'A different account can access the linked audio';
    END IF;
END
$test$;

SET LOCAL ROLE service_role;
DO $test$
DECLARE
    canonical_id text := 'trk_' || rtrim(encode(convert_to('v1:track:deezer:123', 'UTF8'), 'base64'), '=');
    intent jsonb;
    job jsonb := jsonb_build_object('id','job_linked_fixture','provider_name','youtube','status','queued',
        'operation_type','download','created_at',clock_timestamp(),'updated_at',clock_timestamp());
BEGIN
    intent := public.aura_claim_playlist_acquisition('job_linked_fixture');
    IF intent->>'track_id' IS DISTINCT FROM canonical_id OR intent #>> '{metadata,title}' IS DISTINCT FROM 'Title'
        OR public.aura_claim_playlist_acquisition('job_duplicate') IS NOT NULL THEN
        RAISE EXCEPTION 'The link and canonical occurrence must reuse one durable intent with metadata';
    END IF;
    PERFORM public.aura_insert_playlist_download_job((intent->>'user_id')::uuid, canonical_id, (intent->>'lease_token')::uuid, job);
    PERFORM public.aura_finish_playlist_acquisition((intent->>'user_id')::uuid, canonical_id, (intent->>'lease_token')::uuid,
        'failed', 'job_linked_fixture');
END
$test$;
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', 'c0000000-0000-0000-0000-000000000071', true);
DO $test$
BEGIN
    IF NOT (public.aura_get_playlist_acquisitions('linked-audio-fixture', false, 'track:local:10') @>
        '[{"track_id":"track:local:10","status":"admitted","job_id":"job_linked_fixture"}]') THEN
        RAISE EXCEPTION 'Retry did not reuse the canonical job';
    END IF;
END
$test$;
ROLLBACK;
