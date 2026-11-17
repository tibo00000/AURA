BEGIN;

CREATE FUNCTION public.aura_playlist_acquisition_tracks(p_playlist_id text)
RETURNS TABLE(track_id text, audio_track_id text, metadata jsonb, ambiguous boolean)
LANGUAGE sql STABLE SECURITY INVOKER SET search_path = ''
AS $function$
    WITH entries AS (
        SELECT i.track_id, i.position, i.source_metadata,
            CASE WHEN i.track_id ~ '^(track:local:|local:|local_media_)' AND (
                (i.source_metadata->>'provider' = 'deezer' AND i.source_metadata->>'provider_id' ~ '^[1-9][0-9]{0,19}$')
                OR (i.source_metadata->>'provider' = 'spotify' AND i.source_metadata->>'provider_id' ~ '^[A-Za-z0-9]{22}$')
            ) THEN 'trk_' || rtrim(translate(encode(convert_to(
                'v1:track:' || (i.source_metadata->>'provider') || ':' || (i.source_metadata->>'provider_id'),
                'UTF8'), 'base64'), '+/', '-_'), '=') END AS confirmed_id
        FROM public.playlist_items i WHERE i.playlist_id = p_playlist_id
    ), identities AS (
        SELECT e.track_id, count(DISTINCT e.confirmed_id) AS identity_count
        FROM entries e GROUP BY e.track_id
    ), representative AS (
        SELECT DISTINCT ON (e.track_id) e.track_id, e.confirmed_id, e.source_metadata
        FROM entries e ORDER BY e.track_id, e.confirmed_id NULLS LAST, (e.source_metadata IS NULL), e.position
    )
    SELECT r.track_id,
        CASE WHEN i.identity_count = 1 THEN r.confirmed_id ELSE r.track_id END,
        COALESCE(r.source_metadata, '{}'::jsonb), i.identity_count > 1
    FROM representative r JOIN identities i ON i.track_id = r.track_id
$function$;
REVOKE ALL ON FUNCTION public.aura_playlist_acquisition_tracks(text) FROM PUBLIC, anon, authenticated, service_role;

DROP FUNCTION public.aura_get_playlist_acquisitions(text,boolean,text);
CREATE FUNCTION public.aura_get_playlist_acquisitions(
    p_playlist_id text, p_enqueue boolean DEFAULT false, p_retry_track_id text DEFAULT NULL,
    p_existing_track_ids text[] DEFAULT '{}'
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE
    owner_id uuid := auth.uid();
BEGIN
    PERFORM 1 FROM public.playlists WHERE id = p_playlist_id AND user_id = owner_id FOR UPDATE;
    IF NOT FOUND THEN RETURN NULL; END IF;

    IF p_enqueue OR p_retry_track_id IS NOT NULL THEN
        INSERT INTO public.playlist_acquisition_intents AS current_intent(user_id, track_id, metadata)
        SELECT DISTINCT ON (entry.audio_track_id) owner_id, entry.audio_track_id, entry.metadata
        FROM public.aura_playlist_acquisition_tracks(p_playlist_id) entry
        WHERE NOT entry.ambiguous AND (p_enqueue OR entry.track_id = p_retry_track_id)
            AND NOT (entry.track_id = ANY(COALESCE(p_existing_track_ids, '{}')))
        ORDER BY entry.audio_track_id, (entry.metadata = '{}'::jsonb), entry.track_id
        ON CONFLICT (user_id, track_id) DO UPDATE SET metadata = EXCLUDED.metadata
        WHERE current_intent.metadata = '{}'::jsonb AND EXCLUDED.metadata <> '{}'::jsonb;
    END IF;

    IF p_retry_track_id IS NOT NULL THEN
        UPDATE public.playlist_acquisition_intents i SET
            status = CASE WHEN j.id IS NULL THEN 'pending' ELSE 'admitted' END,
            job_id = j.id,
            reserved_job_id = CASE WHEN i.job_id IS NOT NULL AND j.id IS NULL THEN NULL ELSE i.reserved_job_id END,
            error_code = 'retry_requested', next_attempt_at = clock_timestamp(), failure_count = 0,
            lease_token = NULL, lease_until = NULL, updated_at = clock_timestamp()
        FROM public.aura_playlist_acquisition_tracks(p_playlist_id) entry
        LEFT JOIN public.playlist_acquisition_intents existing ON existing.user_id = owner_id AND existing.track_id = entry.audio_track_id
        LEFT JOIN public.download_jobs j ON j.id = existing.job_id AND j.user_id = owner_id AND j.track_id = entry.audio_track_id
        WHERE i.user_id = owner_id AND i.track_id = entry.audio_track_id AND entry.track_id = p_retry_track_id
            AND NOT entry.ambiguous AND NOT (entry.track_id = ANY(COALESCE(p_existing_track_ids, '{}')))
            AND i.status IN ('available', 'source_not_authorized', 'failed', 'cancelled')
            AND (i.lease_until IS NULL OR i.lease_until <= clock_timestamp());
    END IF;

    RETURN COALESCE((
        SELECT jsonb_agg((CASE WHEN entry.ambiguous THEN jsonb_build_object(
            'track_id', entry.track_id, 'status', 'source_not_authorized',
            'error_code', 'source_identity_ambiguous', 'job_id', NULL, 'job_status', NULL
        ) ELSE jsonb_build_object(
            'track_id', entry.track_id, 'status', COALESCE(i.status, 'not_requested'),
            'error_code', i.error_code, 'job_id', i.job_id, 'job_status', j.status
        ) END) || CASE WHEN entry.audio_track_id <> entry.track_id
            THEN jsonb_build_object('audio_track_id', entry.audio_track_id) ELSE '{}'::jsonb END
            ORDER BY entry.track_id)
        FROM public.aura_playlist_acquisition_tracks(p_playlist_id) entry
        LEFT JOIN public.playlist_acquisition_intents i ON i.track_id = entry.audio_track_id AND i.user_id = owner_id
        LEFT JOIN public.download_jobs j ON j.id = i.job_id AND j.track_id = i.track_id AND j.user_id = owner_id
    ), '[]'::jsonb);
END
$function$;
REVOKE ALL ON FUNCTION public.aura_get_playlist_acquisitions(text,boolean,text,text[]) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.aura_get_playlist_acquisitions(text,boolean,text,text[]) TO authenticated;

COMMIT;
