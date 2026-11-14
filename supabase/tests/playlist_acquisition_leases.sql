BEGIN;

INSERT INTO public.profiles(id) VALUES
    ('c0000000-0000-0000-0000-000000000041'),
    ('c0000000-0000-0000-0000-000000000042');
INSERT INTO public.playlists(id,user_id,name)
VALUES ('worker-fixture','c0000000-0000-0000-0000-000000000041','Mix');
INSERT INTO public.playlist_items(id,playlist_id,track_id,position)
VALUES ('worker-entry','worker-fixture','trk_fixture',0);

SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', 'c0000000-0000-0000-0000-000000000041', true);
DO $test$
BEGIN
    IF public.aura_get_playlist_acquisitions('worker-fixture', true) #>> '{0,status}' IS DISTINCT FROM 'pending'
        OR has_function_privilege(current_user, 'public.aura_claim_playlist_acquisition(text,integer)', 'EXECUTE') THEN
        RAISE EXCEPTION 'Enqueue or isolation failed';
    END IF;
END
$test$;
SELECT set_config('request.jwt.claim.sub', 'c0000000-0000-0000-0000-000000000042', true);
DO $test$
BEGIN
    IF public.aura_get_playlist_acquisitions('worker-fixture', true) IS NOT NULL THEN
        RAISE EXCEPTION 'Another account can start or inspect the acquisition';
    END IF;
END
$test$;

SET LOCAL ROLE service_role;
DO $test$
DECLARE
    owner_id uuid := 'c0000000-0000-0000-0000-000000000041';
    first jsonb;
    second jsonb;
    admitted jsonb;
    job jsonb := jsonb_build_object('id','job_fixture1','provider_name','youtube','status','queued',
        'operation_type','download','created_at',clock_timestamp(),'updated_at',clock_timestamp());
BEGIN
    first := public.aura_claim_playlist_acquisition('job_fixture1', 120);
    IF first->>'reserved_job_id' IS DISTINCT FROM 'job_fixture1'
        OR public.aura_claim_playlist_acquisition('job_fixture2', 120) IS NOT NULL THEN
        RAISE EXCEPTION 'Two workers claimed the same live intention';
    END IF;
    admitted := public.aura_insert_playlist_download_job(owner_id, 'trk_fixture', (first->>'lease_token')::uuid, job);
    IF admitted->>'id' IS DISTINCT FROM 'job_fixture1' THEN RAISE EXCEPTION 'Job admission failed'; END IF;
    -- Simulate a process lost after insertion but before it acknowledged the claim.
    UPDATE public.playlist_acquisition_intents SET lease_until = clock_timestamp() - interval '1 second';
    second := public.aura_claim_playlist_acquisition('job_fixture2', 120);
    IF second->>'lease_token' IS NOT DISTINCT FROM first->>'lease_token'
        OR second->>'reserved_job_id' IS DISTINCT FROM 'job_fixture1'
        OR second->>'job_id' IS DISTINCT FROM 'job_fixture1' THEN
        RAISE EXCEPTION 'Restart did not recover the original job';
    END IF;
    IF public.aura_finish_playlist_acquisition(owner_id, 'trk_fixture', (first->>'lease_token')::uuid, 'available')
       OR public.aura_insert_playlist_download_job(owner_id, 'trk_fixture', (first->>'lease_token')::uuid, job) IS NOT NULL THEN
        RAISE EXCEPTION 'Stale lease could mutate an acquisition';
    END IF;
    IF public.aura_restart_playlist_download_job(owner_id, 'trk_fixture', (first->>'lease_token')::uuid,
        'job_fixture1', 'queued', 1) IS NOT NULL THEN
        RAISE EXCEPTION 'Stale lease could restart a job';
    END IF;
    admitted := public.aura_restart_playlist_download_job(owner_id, 'trk_fixture', (second->>'lease_token')::uuid,
        'job_fixture1', 'queued', 1);
    IF admitted->>'attempt_count' IS DISTINCT FROM '2'
        OR public.aura_restart_playlist_download_job(owner_id, 'trk_fixture', (second->>'lease_token')::uuid,
            'job_fixture1', 'queued', 1) IS NOT NULL THEN
        RAISE EXCEPTION 'Recovery did not apply exactly one restart';
    END IF;
    IF NOT public.aura_finish_playlist_acquisition(owner_id, 'trk_fixture',
        (second->>'lease_token')::uuid, 'available', 'job_fixture1')
        OR (SELECT count(*) FROM public.download_jobs WHERE user_id = owner_id) <> 1 THEN
        RAISE EXCEPTION 'Recovery lost or duplicated the admission';
    END IF;
END
$test$;
ROLLBACK;
