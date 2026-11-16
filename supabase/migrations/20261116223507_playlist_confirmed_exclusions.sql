BEGIN;

-- Keep confirmed provider identities independently of an earlier removal of the same local track.
CREATE OR REPLACE FUNCTION public.aura_record_playlist_exclusion()
RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE
    provider text := OLD.source_metadata->>'provider';
    provider_id text := OLD.source_metadata->>'provider_id';
BEGIN
    INSERT INTO public.playlist_source_exclusions(playlist_id, track_id, metadata)
    SELECT p.id, recording.track_id, OLD.source_metadata
    FROM public.playlists p
    CROSS JOIN (
        SELECT OLD.track_id AS track_id
        UNION
        SELECT 'track:' || provider || ':' || provider_id
        WHERE (provider = 'deezer' AND provider_id ~ '^[1-9][0-9]{0,19}$')
           OR (provider = 'spotify' AND provider_id ~ '^[A-Za-z0-9]{22}$')
    ) recording
    WHERE p.id = OLD.playlist_id
    ORDER BY recording.track_id
    ON CONFLICT (playlist_id, track_id) DO UPDATE
        SET metadata = EXCLUDED.metadata
        WHERE playlist_source_exclusions.metadata IS NULL AND EXCLUDED.metadata IS NOT NULL;
    RETURN OLD;
END
$function$;

COMMIT;
