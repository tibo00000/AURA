-- Execute ONLY via server/scripts/test_user_isolation_postgres.py in its disposable DB.
-- These are real PostgreSQL RLS/privilege/constraint assertions, not a Python simulation.
BEGIN;
CREATE TEMP TABLE sec002_results(label text PRIMARY KEY);
GRANT INSERT,SELECT ON sec002_results TO authenticated,anon,service_role;
CREATE FUNCTION pg_temp.check_sec002(ok boolean, label text) RETURNS void
LANGUAGE plpgsql SECURITY INVOKER AS $$
BEGIN
    IF ok IS DISTINCT FROM true THEN RAISE EXCEPTION 'SEC002 assertion failed: %',label; END IF;
    INSERT INTO pg_temp.sec002_results VALUES(label);
END $$;
CREATE FUNCTION pg_temp.deny_sec002(stmt text, label text, codes text[] DEFAULT ARRAY['42501','23503','23505'])
RETURNS void LANGUAGE plpgsql SECURITY INVOKER AS $$
DECLARE rejected boolean := false;
BEGIN
    BEGIN EXECUTE stmt;
    EXCEPTION WHEN OTHERS THEN
        IF SQLSTATE = ANY(codes) THEN rejected := true; ELSE RAISE; END IF;
    END;
    PERFORM pg_temp.check_sec002(rejected,label);
END $$;

INSERT INTO public.profiles(id,display_name) VALUES
 ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa','A'),('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb','B');
INSERT INTO public.user_settings(user_id) VALUES('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'),('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb');
INSERT INTO public.playlists(id,user_id,name) VALUES
 ('pa','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa','A'),('pa2','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa','A2'),
 ('pb','bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb','B');
INSERT INTO public.playlist_items(id,playlist_id,track_id,position) VALUES
 ('ia','pa','shared',0),('ia2','pa','shared',1),('ia-other','pa2','shared',0),('ib','pb','private-b',0);
INSERT INTO public.likes(user_id,track_id) VALUES('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa','shared'),('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb','shared');
INSERT INTO public.playback_snapshots(user_id) VALUES('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'),('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb');
INSERT INTO public.listening_sessions(id,user_id) VALUES('sa','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'),('sb','bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb');
INSERT INTO public.history_items(id,user_id,track_id,listening_session_id) VALUES
 ('ha','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa','shared','sa'),('hb','bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb','private-b','sb');
INSERT INTO public.playback_events(id,user_id,session_id,track_id,event_type) VALUES
 ('ea','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa','sa','shared','play'),('eb','bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb','sb','private-b','play');
INSERT INTO public.processed_operations(operation_id,user_id) VALUES('same','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'),('same','bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb');
INSERT INTO public.processed_batches(batch_id,user_id) VALUES('same','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'),('same','bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb');
INSERT INTO public.user_track_stats(id,user_id) VALUES('sta','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'),('stb','bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb');
INSERT INTO public.recent_searches(id,user_id) VALUES('ra','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'),('rb','bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb');

-- Table-wide permissive policies deliberately left in place: restrictive guards must win.
CREATE POLICY synthetic_legacy_allow_all ON public.playlists FOR ALL TO authenticated USING(true) WITH CHECK(true);
SELECT pg_temp.check_sec002(NOT (SELECT rolbypassrls FROM pg_roles WHERE rolname='authenticated'),'role cannot bypass RLS');
SELECT pg_temp.check_sec002(NOT (SELECT prosecdef FROM pg_proc WHERE oid='public.aura_mutate_playlist_items(text,text,text,jsonb,timestamptz)'::regprocedure),'RPC is security invoker');
SELECT pg_temp.check_sec002(NOT has_function_privilege('anon','public.aura_mutate_playlist_items(text,text,text,jsonb,timestamptz)','EXECUTE'),'anon cannot call RPC');
SELECT pg_temp.check_sec002(NOT has_table_privilege('authenticated','public.download_jobs','SELECT'),'jobs remain server only');
SELECT pg_temp.check_sec002(NOT has_table_privilege('authenticated','public.tracks','SELECT'),'catalogue grants not reopened');

SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',true);
DO $$
DECLARE t text; col text; owner_expr text; count_rows bigint;
BEGIN
    FOR t,col IN VALUES ('profiles','display_name'),('user_settings','sync_enabled'),('playlists','name'),
       ('playlist_items','position'),('likes','liked_at'),('playback_snapshots','position_ms'),
       ('history_items','played_at'),('listening_sessions','total_listening_ms'),('playback_events','event_type'),
       ('processed_operations','processed_at'),('processed_batches','processed_at')
    LOOP
        owner_expr := CASE t WHEN 'profiles' THEN 'id' WHEN 'playlist_items' THEN NULL ELSE 'user_id' END;
        IF owner_expr IS NULL THEN
            EXECUTE format('SELECT count(*) FROM public.%I WHERE playlist_id=''pb''',t) INTO count_rows;
        ELSE
            EXECUTE format('SELECT count(*) FROM public.%I WHERE %I=''bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb''',t,owner_expr) INTO count_rows;
        END IF;
        PERFORM pg_temp.check_sec002(count_rows=0,t||': foreign select hidden');
        EXECUTE format('WITH changed AS (UPDATE public.%I SET %I=%I WHERE %s RETURNING *) SELECT count(*) FROM changed',
            t,col,col,CASE WHEN owner_expr IS NULL THEN 'playlist_id=''pb''' ELSE format('%I=''bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb''',owner_expr) END) INTO count_rows;
        PERFORM pg_temp.check_sec002(count_rows=0,t||': foreign update no rows');
        EXECUTE format('WITH changed AS (DELETE FROM public.%I WHERE %s RETURNING *) SELECT count(*) FROM changed',
            t,CASE WHEN owner_expr IS NULL THEN 'playlist_id=''pb''' ELSE format('%I=''bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb''',owner_expr) END) INTO count_rows;
        PERFORM pg_temp.check_sec002(count_rows=0,t||': foreign delete no rows');
        EXECUTE format('SELECT count(*) FROM public.%I',t) INTO count_rows;
        PERFORM pg_temp.check_sec002(count_rows>0,t||': own select works');
    END LOOP;
END $$;

SELECT pg_temp.deny_sec002($q$UPDATE public.playlists SET user_id='bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb' WHERE id='pa'$q$,'cannot transfer playlist owner');
SELECT pg_temp.deny_sec002($q$INSERT INTO public.playlists(id,user_id,name) VALUES('evil','bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb','x')$q$,'cannot insert foreign owner');
SELECT pg_temp.deny_sec002($q$INSERT INTO public.playlists(id,user_id,name) VALUES('pb','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa','x') ON CONFLICT(id) DO UPDATE SET user_id=excluded.user_id,name=excluded.name$q$,'cannot hijack playlist upsert');
SELECT pg_temp.deny_sec002($q$INSERT INTO public.history_items(id,user_id,track_id) VALUES('hb','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa','x') ON CONFLICT(id) DO UPDATE SET user_id=excluded.user_id$q$,'cannot hijack history upsert');
SELECT pg_temp.deny_sec002($q$INSERT INTO public.listening_sessions(id,user_id) VALUES('sb','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa') ON CONFLICT(id) DO UPDATE SET user_id=excluded.user_id$q$,'cannot hijack session upsert');
SELECT pg_temp.deny_sec002($q$INSERT INTO public.playback_events(id,user_id,session_id,track_id,event_type) VALUES('eb','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa','sa','x','play') ON CONFLICT(id) DO UPDATE SET user_id=excluded.user_id$q$,'cannot hijack event upsert');
SELECT pg_temp.deny_sec002($q$INSERT INTO public.playlist_items(id,playlist_id,track_id,position) VALUES('evil','pb','x',9)$q$,'cannot insert into foreign parent');
SELECT pg_temp.deny_sec002($q$UPDATE public.playlist_items SET playlist_id='pb' WHERE id='ia'$q$,'cannot reparent item');
SELECT pg_temp.deny_sec002($q$INSERT INTO public.history_items(id,user_id,track_id,listening_session_id) VALUES('evil','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa','x','sb')$q$,'history foreign session denied');
SELECT pg_temp.deny_sec002($q$UPDATE public.history_items SET listening_session_id='sb' WHERE id='ha'$q$,'history reparent denied');
SELECT pg_temp.deny_sec002($q$INSERT INTO public.playback_events(id,user_id,session_id,track_id,event_type) VALUES('evil','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa','sb','x','play')$q$,'event foreign session denied');
SELECT pg_temp.deny_sec002($q$UPDATE public.playback_events SET session_id='sb' WHERE id='ea'$q$,'event reparent denied');
SELECT pg_temp.deny_sec002($q$INSERT INTO public.processed_operations(operation_id,user_id) VALUES('foreign','bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb')$q$,'cannot write foreign operation marker');
SELECT pg_temp.deny_sec002($q$UPDATE public.processed_batches SET user_id='bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb' WHERE batch_id='same'$q$,'cannot transfer batch marker');
SELECT pg_temp.check_sec002((SELECT count(*)=1 FROM public.processed_operations WHERE operation_id='same'),'same operation ID isolated');
SELECT pg_temp.check_sec002((SELECT count(*)=1 FROM public.processed_batches WHERE batch_id='same'),'same batch ID isolated');
SELECT pg_temp.check_sec002((SELECT count(*)=1 FROM public.user_track_stats),'optional stats scoped');
SELECT pg_temp.check_sec002((SELECT count(*)=1 FROM public.recent_searches),'optional searches scoped');

-- A valid own order/token does not authorize a foreign or another-parent item.
SELECT pg_temp.deny_sec002($q$SELECT public.aura_mutate_playlist_items('pa',NULL,'reorder','{"items":[{"playlist_item_id":"ia","position":1},{"playlist_item_id":"ib","position":0}]}',(SELECT updated_at FROM public.playlists WHERE id='pa'))$q$,'mixed A B order rejected atomically');
SELECT pg_temp.deny_sec002($q$SELECT public.aura_mutate_playlist_items('pa',NULL,'reorder','{"items":[{"playlist_item_id":"ia-other","position":0}]}',(SELECT updated_at FROM public.playlists WHERE id='pa'))$q$,'own other-parent item rejected');
SELECT pg_temp.deny_sec002($q$SELECT public.aura_mutate_playlist_items('pa','ib','insert','{"track_id":"x","position":0}')$q$,'hidden ID collision rolls back shift');
SELECT pg_temp.check_sec002((SELECT position=0 FROM public.playlist_items WHERE id='ia') AND (SELECT position=1 FROM public.playlist_items WHERE id='ia2'),'failed inserts and mixed orders preserve positions');
SELECT pg_temp.deny_sec002($q$SELECT public.aura_mutate_playlist_items('pb','ib','delete','{}')$q$,'RPC foreign parent denied');
SELECT pg_temp.deny_sec002($q$SELECT public.aura_mutate_playlist_items('missing','x','insert','{"track_id":"x"}')$q$,'RPC missing parent denied');
SELECT pg_temp.deny_sec002($q$SELECT public.aura_mutate_playlist_items('pa',NULL,'reorder','{"items":[{"playlist_item_id":"ia","position":0},{"playlist_item_id":"ia2","position":0}]}',(SELECT updated_at FROM public.playlists WHERE id='pa'))$q$,'duplicate positions rejected',ARRAY['42501']);
SELECT pg_temp.deny_sec002($q$SELECT public.aura_mutate_playlist_items('pa','new',NULL,'{}')$q$,'null action rejected',ARRAY['P0001']);

SELECT pg_temp.check_sec002(public.aura_mutate_playlist_items('pa',NULL,'reorder','{"items":[{"playlist_item_id":"ia","position":1},{"playlist_item_id":"ia2","position":0}]}',(SELECT updated_at FROM public.playlists WHERE id='pa'))->>'status'='applied','own swap works');
SELECT pg_temp.check_sec002((SELECT position=1 FROM public.playlist_items WHERE id='ia'),'swap position persisted');
SELECT pg_temp.check_sec002(public.aura_mutate_playlist_items('pa',NULL,'reorder','{"items":[]}','2000-01-01Z')->>'status'='conflict','concurrent stale version returns conflict');
SELECT pg_temp.check_sec002(public.aura_mutate_playlist_items('pa','ia-new','insert','{"track_id":"shared","position":0}')->>'status'='applied','insert shifts own playlist');
SELECT pg_temp.check_sec002(public.aura_mutate_playlist_items('pa','ia-new','insert','{"track_id":"shared","position":0}')->>'status'='ignored_duplicate','item retry idempotent');
SELECT pg_temp.check_sec002((SELECT count(*)=3 FROM public.playlist_items WHERE playlist_id='pa'),'multiple occurrences of same track supported');
SELECT pg_temp.check_sec002(public.aura_mutate_playlist_items('pa','ia-new','delete','{}')->>'status'='applied','own item delete');
SELECT pg_temp.check_sec002(public.aura_mutate_playlist_items('pa','ia-new','delete','{}')->>'status'='ignored_duplicate','deleted item retry');
INSERT INTO public.history_items(id,user_id,track_id) VALUES('optional-session','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa','shared');
SELECT pg_temp.check_sec002((SELECT listening_session_id IS NULL FROM public.history_items WHERE id='optional-session'),'history session remains optional');

-- Composite FK protects even a privileged worker; deleting own session retains history.
RESET ROLE;
SELECT pg_temp.deny_sec002($q$INSERT INTO public.playback_events(id,user_id,session_id,track_id,event_type) VALUES('bad-worker','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa','sb','x','play')$q$,'worker cannot create inconsistent event owner');
SELECT pg_temp.deny_sec002($q$INSERT INTO public.history_items(id,user_id,track_id,listening_session_id) VALUES('bad-worker','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa','x','sb')$q$,'worker cannot create inconsistent history owner');
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',true);
DELETE FROM public.listening_sessions WHERE id='sa';
SELECT pg_temp.check_sec002((SELECT user_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND listening_session_id IS NULL FROM public.history_items WHERE id='ha'),'session deletion nulls only history session');
SELECT pg_temp.check_sec002((SELECT count(*)=0 FROM public.playback_events WHERE id='ea'),'own event cascades');

SELECT set_config('request.jwt.claim.sub','bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',true);
SELECT pg_temp.check_sec002((SELECT count(*)=1 FROM public.playlists WHERE id='pb'),'B still owns playlist');
SELECT pg_temp.check_sec002((SELECT position=0 FROM public.playlist_items WHERE id='ib'),'B item intact');
SELECT pg_temp.check_sec002((SELECT listening_session_id='sb' FROM public.history_items WHERE id='hb'),'B history intact');
SELECT pg_temp.check_sec002((SELECT session_id='sb' FROM public.playback_events WHERE id='eb'),'B event intact');
UPDATE public.playlists SET name='B-updated' WHERE id='pb';
SELECT pg_temp.check_sec002((SELECT name='B-updated' FROM public.playlists WHERE id='pb'),'B own update works');
SELECT pg_temp.check_sec002(public.aura_mutate_playlist_items('pb','b-new','insert','{"track_id":"shared","position":0}')->>'status'='applied','B own item insert works');
SELECT pg_temp.check_sec002((SELECT count(*)=0 FROM public.playlists WHERE id='pa'),'B cannot read A');

SET LOCAL ROLE anon;
SELECT set_config('request.jwt.claim.sub','',true);
SELECT pg_temp.deny_sec002('SELECT * FROM public.playlists','anon table denied');
SELECT pg_temp.deny_sec002($q$SELECT public.aura_mutate_playlist_items('pa','x','insert','{"track_id":"x"}')$q$,'anon RPC denied');
RESET ROLE;
SELECT 'SEC002_SQL_PASSED='||count(*) FROM pg_temp.sec002_results;
ROLLBACK;
