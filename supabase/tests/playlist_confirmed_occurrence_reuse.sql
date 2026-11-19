BEGIN;
INSERT INTO public.profiles(id) VALUES ('c0000000-0000-0000-0000-000000000081');
INSERT INTO public.playlists(id,user_id,name,source_provider,source_external_id)
VALUES ('editions-fixture','c0000000-0000-0000-0000-000000000081','Editions','deezer','123'),
       ('other-editions','c0000000-0000-0000-0000-000000000081','Other','deezer','456');
INSERT INTO public.playlist_items(id,playlist_id,track_id,position,source_metadata) VALUES
 ('first-edition','editions-fixture','track:local:hera',4,
  '{"provider":"deezer","provider_id":"111","title":"Héra","artist":"Georgio","album":"Manual album"}'),
 ('foreign-occurrence','other-editions','track:local:foreign',0,NULL);
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub','c0000000-0000-0000-0000-000000000081',true);
DO $test$
DECLARE
    recording text := 'trk_' || rtrim(encode(convert_to('v1:track:deezer:222','UTF8'),'base64'),'=');
    item jsonb;
    result jsonb;
    first_before jsonb;
    second_id text;
BEGIN
    item := jsonb_build_object('track_id',recording,'provider','deezer','provider_id','222',
        'title','Héra','artist','Georgio','requires_confirmation',true);
    SELECT to_jsonb(i) INTO first_before FROM public.playlist_items i WHERE id='first-edition';
    result := public.aura_commit_playlist_refresh('editions-fixture','00000000-0000-0000-0000-000000000081',1,
        jsonb_build_array(item),'[]',jsonb_build_object(recording,'first-edition'));
    IF result->>'added_count' <> '1' OR jsonb_array_length(result->'uncertain') <> 0
       OR (SELECT to_jsonb(i) FROM public.playlist_items i WHERE id='first-edition') IS DISTINCT FROM first_before
       OR NOT EXISTS (SELECT 1 FROM public.playlist_items WHERE playlist_id='editions-fixture'
          AND id<>'first-edition' AND track_id='track:local:hera' AND position=5
          AND source_metadata->>'provider_id'='222') THEN
        RAISE EXCEPTION 'Explicit reuse must preserve the first edition and local audio ID';
    END IF;
    -- Lost acknowledgement and a concurrent second confirmation must both be idempotent.
    PERFORM public.aura_commit_playlist_refresh('editions-fixture','00000000-0000-0000-0000-000000000081',1,
        jsonb_build_array(item),'[]',jsonb_build_object(recording,'first-edition'));
    PERFORM public.aura_commit_playlist_refresh('editions-fixture','00000000-0000-0000-0000-000000000082',1,
        jsonb_build_array(item),'[]',jsonb_build_object(recording,'first-edition'));
    IF (SELECT count(*) FROM public.playlist_items WHERE playlist_id='editions-fixture') <> 2 THEN
        RAISE EXCEPTION 'A repeated confirmation duplicated an edition';
    END IF;
    -- A destination from another playlist must not mutate either playlist.
    item := item || jsonb_build_object('track_id','trk_' || rtrim(encode(convert_to('v1:track:deezer:333','UTF8'),'base64'),'='),'provider_id','333');
    result := public.aura_commit_playlist_refresh('editions-fixture','00000000-0000-0000-0000-000000000083',1,
        jsonb_build_array(item || jsonb_build_object('track_id',
            'trk_' || rtrim(encode(convert_to('v1:track:deezer:444','UTF8'),'base64'),'='),
            'provider_id','444','requires_confirmation',false), item),
        '[]',jsonb_build_object(item->>'track_id','foreign-occurrence'));
    IF result->>'reason' IS DISTINCT FROM 'playlist_link_changed'
       OR (SELECT count(*) FROM public.playlist_items WHERE playlist_id='editions-fixture') <> 2 THEN
        RAISE EXCEPTION 'A stale or foreign destination must produce a specific conflict';
    END IF;
    result := public.aura_commit_playlist_refresh('editions-fixture','00000000-0000-0000-0000-000000000084',2,
        jsonb_build_array(item));
    IF result->>'status' <> 'conflict' OR result ? 'reason' THEN
        RAISE EXCEPTION 'A real source revision conflict must remain distinct';
    END IF;
    SELECT id INTO second_id FROM public.playlist_items WHERE playlist_id='editions-fixture' AND id<>'first-edition';
    PERFORM public.aura_apply_playlist_operation('remove-confirmed-edition','playlist_item',second_id,'delete',
        '{"playlist_id":"editions-fixture"}');
    item := item || jsonb_build_object('track_id',recording,'provider_id','222');
    result := public.aura_commit_playlist_refresh('editions-fixture','00000000-0000-0000-0000-000000000085',1,
        jsonb_build_array(item),'[]',jsonb_build_object(recording,'first-edition'));
    IF result->>'excluded_count' <> '1'
       OR (SELECT to_jsonb(i) FROM public.playlist_items i WHERE id='first-edition') IS DISTINCT FROM first_before THEN
        RAISE EXCEPTION 'Confirmation bypassed a manual exclusion or changed the original';
    END IF;
END
$test$;
ROLLBACK;
