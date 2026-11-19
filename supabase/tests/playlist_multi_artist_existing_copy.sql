BEGIN;
INSERT INTO public.profiles(id) VALUES ('c0000000-0000-0000-0000-000000000091');
INSERT INTO public.playlists(id,user_id,name,source_provider,source_external_id)
VALUES ('credits-fixture','c0000000-0000-0000-0000-000000000091','Credits','deezer','123');
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub','c0000000-0000-0000-0000-000000000091',true);
DO $test$
DECLARE
 recording text := 'trk_' || rtrim(encode(convert_to('v1:track:deezer:991','UTF8'),'base64'),'=');
 artist text := 'art_' || rtrim(encode(convert_to('v1:artist:deezer:991','UTF8'),'base64'),'=');
 item jsonb; result jsonb; occurrence text; saved_position integer; saved_added timestamptz;
BEGIN
 item:=jsonb_build_object('track_id',recording,'provider','deezer','provider_id','991','title','Song','artist','Tyler, The Creator',
   'artists',jsonb_build_array(jsonb_build_object('id',artist,'name','Tyler, The Creator','provider','deezer','provider_id','991','role','primary')));
 result:=public.aura_commit_playlist_refresh('credits-fixture','00000000-0000-0000-0000-000000000091',1,jsonb_build_array(item));
 SELECT id,position,added_at INTO occurrence,saved_position,saved_added FROM public.playlist_items WHERE playlist_id='credits-fixture';
 IF result->>'added_count'<>'1' OR (SELECT source_metadata->'artists'->0->>'name' FROM public.playlist_items WHERE id=occurrence)<>'Tyler, The Creator' THEN
   RAISE EXCEPTION 'Ordered credits were not preserved';
 END IF;
 result:=public.aura_commit_playlist_refresh('credits-fixture','00000000-0000-0000-0000-000000000092',1,jsonb_build_array(item||'{"requires_confirmation":true}'));
 IF jsonb_array_length(result->'uncertain')<>1 THEN RAISE EXCEPTION 'An already imported occurrence cannot be reviewed'; END IF;
 result:=public.aura_commit_playlist_refresh('credits-fixture','00000000-0000-0000-0000-000000000093',1,jsonb_build_array(item), '[]',
   jsonb_build_object(recording,'aura-track-link:track:local:confirmed'));
 IF result->>'status'<>'applied' OR (SELECT count(*) FROM public.playlist_items WHERE playlist_id='credits-fixture')<>1
 OR NOT EXISTS(SELECT 1 FROM public.playlist_items WHERE id=occurrence AND track_id='track:local:confirmed' AND position=saved_position AND added_at=saved_added) THEN
   RAISE EXCEPTION 'A library confirmation created a temporary occurrence or lost its identity';
 END IF;
 PERFORM public.aura_commit_playlist_refresh('credits-fixture','00000000-0000-0000-0000-000000000093',1,jsonb_build_array(item),'[]',jsonb_build_object(recording,'aura-track-link:track:local:confirmed'));
 result:=public.aura_commit_playlist_refresh('credits-fixture','00000000-0000-0000-0000-000000000094',1,jsonb_build_array((item-'artists')||'{"requires_confirmation":true}'));
 IF jsonb_array_length(result->'uncertain')<>0 OR (SELECT count(*) FROM public.playlist_items WHERE playlist_id='credits-fixture')<>1
 OR (SELECT jsonb_array_length(source_metadata->'artists') FROM public.playlist_items WHERE id=occurrence)<>1 THEN
   RAISE EXCEPTION 'Old clients or replay erased credits or repeated a confirmed choice';
 END IF;
 PERFORM public.aura_apply_playlist_operation('credits-remove','playlist_item',occurrence,'delete','{"playlist_id":"credits-fixture"}');
 result:=public.aura_commit_playlist_refresh('credits-fixture','00000000-0000-0000-0000-000000000095',1,jsonb_build_array(item), '[]',jsonb_build_object(recording,'aura-track-link:track:local:confirmed'));
 IF result->>'excluded_count'<>'1' OR EXISTS(SELECT 1 FROM public.playlist_items WHERE playlist_id='credits-fixture') THEN
   RAISE EXCEPTION 'Direct library reuse bypassed a manual exclusion';
 END IF;
END
$test$;
ROLLBACK;
