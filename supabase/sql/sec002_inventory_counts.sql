-- SECOND STEP only: metadata must first confirm these tables/columns and complete read rights.
-- Requires SELECT over all relevant rows, not an end-user JWT restricted by RLS.
-- Aggregate counts only; never return row IDs, owner IDs, account names or row content.
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout = '20s';
SET LOCAL lock_timeout = '2s';
SELECT jsonb_build_object(
 'events_invalid_session',(SELECT count(*) FROM public.playback_events e LEFT JOIN public.listening_sessions s ON s.id=e.session_id WHERE s.id IS NULL OR e.user_id IS DISTINCT FROM s.user_id),
 'history_invalid_session',(SELECT count(*) FROM public.history_items h LEFT JOIN public.listening_sessions s ON s.id=h.listening_session_id WHERE h.listening_session_id IS NOT NULL AND (s.id IS NULL OR h.user_id IS DISTINCT FROM s.user_id)),
 'operation_orphan_owner',(SELECT count(*) FROM public.processed_operations m LEFT JOIN public.profiles p ON p.id=m.user_id WHERE p.id IS NULL),
 'playlist_orphan_owner',(SELECT count(*) FROM public.playlists x LEFT JOIN public.profiles p ON p.id=x.user_id WHERE p.id IS NULL),
 'item_orphan_parent',(SELECT count(*) FROM public.playlist_items i LEFT JOIN public.playlists p ON p.id=i.playlist_id WHERE p.id IS NULL),
 'duplicate_playlist_positions',(SELECT count(*) FROM (SELECT playlist_id,position FROM public.playlist_items GROUP BY playlist_id,position HAVING count(*)>1) duplicates),
 'negative_playlist_positions',(SELECT count(*) FROM public.playlist_items WHERE position<0),
 'row_counts',jsonb_build_object('profiles',(SELECT count(*) FROM public.profiles),
   'playlists',(SELECT count(*) FROM public.playlists),'playlist_items',(SELECT count(*) FROM public.playlist_items),
   'listening_sessions',(SELECT count(*) FROM public.listening_sessions),'history_items',(SELECT count(*) FROM public.history_items),
   'playback_events',(SELECT count(*) FROM public.playback_events),'processed_operations',(SELECT count(*) FROM public.processed_operations))
);
ROLLBACK;
