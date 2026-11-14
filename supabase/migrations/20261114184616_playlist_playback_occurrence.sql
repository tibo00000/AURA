BEGIN;
ALTER TABLE public.playback_snapshots ADD COLUMN playlist_occurrence_id text;
COMMIT;
