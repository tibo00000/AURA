BEGIN;

CREATE TABLE public.playlist_acquisition_intents (
    user_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    track_id text NOT NULL CHECK (length(track_id) BETWEEN 1 AND 200),
    metadata jsonb NOT NULL CHECK (jsonb_typeof(metadata) = 'object'),
    status text NOT NULL DEFAULT 'pending' CHECK (status IN (
        'pending', 'admitted', 'available', 'source_not_authorized', 'failed', 'cancelled'
    )),
    reserved_job_id text UNIQUE,
    job_id text,
    lease_token uuid,
    lease_until timestamptz,
    next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    failure_count integer NOT NULL DEFAULT 0 CHECK (failure_count >= 0),
    error_code text,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (user_id, track_id),
    CHECK ((lease_token IS NULL) = (lease_until IS NULL)),
    CHECK (status <> 'admitted' OR job_id IS NOT NULL)
);
CREATE INDEX playlist_acquisition_intents_due
    ON public.playlist_acquisition_intents(next_attempt_at, user_id, track_id)
    WHERE status IN ('pending', 'admitted');
ALTER TABLE public.playlist_acquisition_intents ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.playlist_acquisition_intents FROM PUBLIC, anon, authenticated, service_role;
GRANT SELECT, INSERT, UPDATE ON TABLE public.playlist_acquisition_intents TO service_role;
CREATE POLICY playlist_acquisition_worker ON public.playlist_acquisition_intents
    FOR ALL TO service_role USING (true) WITH CHECK (true);

-- Called inside the transaction owning the playlist mutation. This function has no client grant.
CREATE FUNCTION public.aura_enqueue_playlist_acquisition(
    p_user_id uuid, p_track_id text, p_metadata jsonb
) RETURNS void
LANGUAGE sql SECURITY INVOKER SET search_path = ''
AS $function$
    INSERT INTO public.playlist_acquisition_intents(user_id, track_id, metadata)
    VALUES (p_user_id, p_track_id, p_metadata)
    ON CONFLICT (user_id, track_id) DO NOTHING;
$function$;
REVOKE ALL ON FUNCTION public.aura_enqueue_playlist_acquisition(uuid,text,jsonb)
    FROM PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.aura_enqueue_playlist_acquisition(uuid,text,jsonb) TO service_role;

COMMIT;
