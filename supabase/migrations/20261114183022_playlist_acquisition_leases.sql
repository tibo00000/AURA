BEGIN;

CREATE FUNCTION public.aura_claim_playlist_acquisition(p_job_id text, p_lease_seconds integer DEFAULT 120)
RETURNS jsonb
LANGUAGE plpgsql SECURITY INVOKER SET search_path = ''
AS $function$
DECLARE
    claimed public.playlist_acquisition_intents%ROWTYPE;
    started_at timestamptz := clock_timestamp();
BEGIN
    IF p_job_id IS NULL OR p_job_id !~ '^job_[A-Za-z0-9_-]{1,124}$'
       OR p_lease_seconds IS NULL OR p_lease_seconds NOT BETWEEN 1 AND 600 THEN
        RAISE EXCEPTION USING ERRCODE = '22023';
    END IF;
    WITH candidate AS (
        SELECT i.user_id, i.track_id
        FROM public.playlist_acquisition_intents i
        WHERE i.status IN ('pending', 'admitted') AND i.next_attempt_at <= started_at
           AND (i.lease_until IS NULL OR i.lease_until <= started_at)
        ORDER BY i.next_attempt_at, i.user_id, i.track_id
        LIMIT 1 FOR UPDATE SKIP LOCKED
    )
    UPDATE public.playlist_acquisition_intents i SET
        lease_token = gen_random_uuid(), lease_until = started_at + make_interval(secs => p_lease_seconds),
        reserved_job_id = COALESCE(i.reserved_job_id, p_job_id), updated_at = started_at
    FROM candidate
    WHERE i.user_id = candidate.user_id AND i.track_id = candidate.track_id RETURNING i.* INTO claimed;
    IF NOT FOUND THEN RETURN NULL; END IF;
    RETURN to_jsonb(claimed);
END
$function$;

CREATE FUNCTION public.aura_finish_playlist_acquisition(
    p_user_id uuid, p_track_id text, p_lease_token uuid, p_status text,
    p_job_id text DEFAULT NULL, p_error_code text DEFAULT NULL, p_retry_seconds integer DEFAULT 0
) RETURNS boolean
LANGUAGE plpgsql SECURITY INVOKER SET search_path = ''
AS $function$
BEGIN
    UPDATE public.playlist_acquisition_intents i SET
        status = p_status, job_id = COALESCE(p_job_id, i.job_id), error_code = p_error_code,
        failure_count = CASE WHEN p_error_code IS NULL THEN 0 ELSE i.failure_count + 1 END,
        next_attempt_at = clock_timestamp() + make_interval(secs => greatest(0, least(p_retry_seconds, 3600))),
        lease_token = NULL, lease_until = NULL, updated_at = clock_timestamp()
    WHERE i.user_id = p_user_id AND i.track_id = p_track_id
      AND i.lease_token = p_lease_token AND i.lease_until > clock_timestamp();
    RETURN FOUND;
END
$function$;

CREATE FUNCTION public.aura_get_playlist_acquisitions(
    p_playlist_id text, p_enqueue boolean DEFAULT false, p_retry_track_id text DEFAULT NULL
)
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE
    owner_id uuid := auth.uid();
BEGIN
    PERFORM 1 FROM public.playlists WHERE id = p_playlist_id AND user_id = owner_id FOR UPDATE;
    IF NOT FOUND THEN RETURN NULL; END IF;
    IF p_enqueue THEN
        INSERT INTO public.playlist_acquisition_intents(user_id, track_id, metadata)
        SELECT DISTINCT ON (i.track_id) owner_id, i.track_id, COALESCE(i.source_metadata, '{}'::jsonb)
        FROM public.playlist_items i WHERE i.playlist_id = p_playlist_id
        ORDER BY i.track_id, i.position
        ON CONFLICT (user_id, track_id) DO NOTHING;
    END IF;
    IF p_retry_track_id IS NOT NULL THEN
        UPDATE public.playlist_acquisition_intents i SET
            status = CASE WHEN i.job_id IS NULL OR NOT EXISTS (
                SELECT 1 FROM public.download_jobs j WHERE j.id = i.job_id AND j.user_id = owner_id
            ) THEN 'pending' ELSE 'admitted' END,
            job_id = CASE WHEN EXISTS (
                SELECT 1 FROM public.download_jobs j WHERE j.id = i.job_id AND j.user_id = owner_id
            ) THEN i.job_id ELSE NULL END,
            reserved_job_id = CASE WHEN i.job_id IS NOT NULL AND NOT EXISTS (
                SELECT 1 FROM public.download_jobs j WHERE j.id = i.job_id AND j.user_id = owner_id
            ) THEN NULL ELSE i.reserved_job_id END,
            error_code = 'retry_requested', next_attempt_at = clock_timestamp(), failure_count = 0,
            lease_token = NULL, lease_until = NULL, updated_at = clock_timestamp()
        WHERE i.user_id = owner_id AND i.track_id = p_retry_track_id
            AND i.status IN ('available', 'source_not_authorized', 'failed', 'cancelled')
            AND (i.lease_until IS NULL OR i.lease_until <= clock_timestamp())
            AND EXISTS (SELECT 1 FROM public.playlist_items entry
                WHERE entry.playlist_id = p_playlist_id AND entry.track_id = i.track_id);
    END IF;
    RETURN COALESCE((
        SELECT jsonb_agg(jsonb_build_object(
            'track_id', entry.track_id, 'status', COALESCE(i.status, 'not_requested'),
            'error_code', i.error_code, 'job_id', i.job_id, 'job_status', j.status
        ) ORDER BY entry.track_id)
        FROM (SELECT DISTINCT track_id FROM public.playlist_items WHERE playlist_id = p_playlist_id) entry
        LEFT JOIN public.playlist_acquisition_intents i ON i.track_id = entry.track_id AND i.user_id = owner_id
        LEFT JOIN public.download_jobs j ON j.id = i.job_id AND j.track_id = i.track_id AND j.user_id = owner_id
    ), '[]'::jsonb);
END
$function$;

-- Fence insertion and link the reserved job before a response can be lost.
-- The downloader still owns validation, locks, publication and status changes.
CREATE FUNCTION public.aura_insert_playlist_download_job(
    p_user_id uuid, p_track_id text, p_lease_token uuid, p_job jsonb
) RETURNS jsonb
LANGUAGE plpgsql SECURITY INVOKER SET search_path = ''
AS $function$
DECLARE
    intention public.playlist_acquisition_intents%ROWTYPE;
    job public.download_jobs%ROWTYPE;
BEGIN
    SELECT * INTO intention FROM public.playlist_acquisition_intents i
    WHERE i.user_id = p_user_id AND i.track_id = p_track_id
      AND i.lease_token = p_lease_token AND i.lease_until > clock_timestamp() FOR UPDATE;
    IF NOT FOUND THEN RETURN NULL; END IF;
    IF p_job->>'id' IS DISTINCT FROM intention.reserved_job_id OR p_job->>'status' IS DISTINCT FROM 'queued'
       OR p_job->>'operation_type' IS DISTINCT FROM 'download' THEN RAISE EXCEPTION USING ERRCODE = '22023'; END IF;
    INSERT INTO public.download_jobs(id, user_id, track_id, provider_name, status, created_at, updated_at, attempt_count)
    VALUES(intention.reserved_job_id, p_user_id, p_track_id, p_job->>'provider_name', 'queued',
        (p_job->>'created_at')::timestamptz, (p_job->>'updated_at')::timestamptz, 1)
    ON CONFLICT (id) DO NOTHING;
    SELECT * INTO job FROM public.download_jobs j
    WHERE j.id = intention.reserved_job_id AND j.user_id = p_user_id AND j.track_id = p_track_id;
    IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE = '23505'; END IF;
    UPDATE public.playlist_acquisition_intents SET job_id = job.id, status = 'admitted'
    WHERE user_id = p_user_id AND track_id = p_track_id;
    RETURN to_jsonb(job);
END
$function$;

REVOKE ALL ON FUNCTION public.aura_claim_playlist_acquisition(text,integer),
    public.aura_finish_playlist_acquisition(uuid,text,uuid,text,text,text,integer),
    public.aura_insert_playlist_download_job(uuid,text,uuid,jsonb)
    FROM PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.aura_claim_playlist_acquisition(text,integer),
    public.aura_finish_playlist_acquisition(uuid,text,uuid,text,text,text,integer),
    public.aura_insert_playlist_download_job(uuid,text,uuid,jsonb) TO service_role;
REVOKE ALL ON FUNCTION public.aura_get_playlist_acquisitions(text,boolean,text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.aura_get_playlist_acquisitions(text,boolean,text) TO authenticated;

CREATE FUNCTION public.aura_restart_playlist_download_job(
    p_user_id uuid, p_track_id text, p_lease_token uuid, p_job_id text, p_status text, p_attempt integer
) RETURNS jsonb
LANGUAGE plpgsql SECURITY INVOKER SET search_path = ''
AS $function$
DECLARE job public.download_jobs%ROWTYPE;
BEGIN
    PERFORM 1 FROM public.playlist_acquisition_intents i
    WHERE i.user_id = p_user_id AND i.track_id = p_track_id AND i.reserved_job_id = p_job_id
      AND i.lease_token = p_lease_token AND i.lease_until > clock_timestamp() FOR UPDATE;
    IF NOT FOUND THEN RETURN NULL; END IF;
    UPDATE public.download_jobs d SET
        status = 'queued', progress_percent = 0, error_code = NULL, error_message = NULL,
        candidates = '[]', attempt_count = d.attempt_count + 1, updated_at = clock_timestamp()
    WHERE d.id = p_job_id AND d.user_id = p_user_id AND d.track_id = p_track_id
      AND d.status = p_status AND d.attempt_count = p_attempt
    RETURNING d.* INTO job;
    IF NOT FOUND THEN RETURN NULL; END IF;
    RETURN to_jsonb(job);
END
$function$;
REVOKE ALL ON FUNCTION public.aura_restart_playlist_download_job(uuid,text,uuid,text,text,integer)
    FROM PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.aura_restart_playlist_download_job(uuid,text,uuid,text,text,integer) TO service_role;

GRANT SELECT, INSERT, UPDATE ON TABLE public.download_jobs TO service_role;

COMMIT;
