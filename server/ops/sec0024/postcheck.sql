-- Metadata only, after migration. No private rows, JWTs or function bodies returned.
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout='20s';
SET LOCAL lock_timeout='2s';
SELECT jsonb_build_object(
 'read_only',current_setting('transaction_read_only'),
 'guards',(SELECT count(*) FROM pg_policy p JOIN pg_class c ON c.oid=p.polrelid JOIN pg_namespace n ON n.oid=c.relnamespace
  WHERE n.nspname='public' AND p.polname='sec002_owner_guard' AND NOT p.polpermissive AND c.relrowsecurity),
 'private_tables_rls',(SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public'
  AND c.relrowsecurity AND c.relname IN ('profiles','user_settings','playlists','playlist_items','likes','playback_snapshots','history_items',
   'listening_sessions','playback_events','processed_operations','processed_batches')),
 'client_privileges_safe',(SELECT bool_and(has_table_privilege('authenticated',name,'SELECT')
  AND has_table_privilege('authenticated',name,'INSERT') AND has_table_privilege('authenticated',name,'UPDATE')
  AND has_table_privilege('authenticated',name,'DELETE') AND NOT has_table_privilege('authenticated',name,'TRUNCATE,REFERENCES,TRIGGER')
  AND NOT has_table_privilege('anon',name,'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER'))
  FROM unnest(ARRAY['public.profiles','public.user_settings','public.playlists','public.playlist_items','public.likes','public.playback_snapshots',
   'public.history_items','public.listening_sessions','public.playback_events','public.processed_operations','public.processed_batches']) names(name)),
 'jobs_clients_denied',NOT has_table_privilege('anon','public.download_jobs','SELECT,INSERT,UPDATE,DELETE,TRUNCATE')
  AND NOT has_table_privilege('authenticated','public.download_jobs','SELECT,INSERT,UPDATE,DELETE,TRUNCATE'),
 'composite_foreign_keys',(SELECT jsonb_agg(jsonb_build_object('name',conname,'validated',convalidated,'delete',confdeltype) ORDER BY conname)
  FROM pg_constraint WHERE conname IN ('sec002_event_session_owner_fk','sec002_history_session_owner_fk')),
 'marker_keys',(SELECT jsonb_agg(jsonb_build_object('table',c.relname,'columns',ARRAY(SELECT a.attname FROM unnest(p.conkey) WITH ORDINALITY u(attnum,ord)
  JOIN pg_attribute a ON a.attrelid=p.conrelid AND a.attnum=u.attnum ORDER BY u.ord)) ORDER BY c.relname)
  FROM pg_constraint p JOIN pg_class c ON c.oid=p.conrelid JOIN pg_namespace n ON n.oid=c.relnamespace
  WHERE n.nspname='public' AND c.relname IN ('processed_operations','processed_batches') AND p.contype='p'),
 'positions_deferrable',(SELECT condeferrable AND NOT condeferred AND convalidated FROM pg_constraint
  WHERE conrelid='public.playlist_items'::regclass AND conname='unique_playlist_position'),
 'rpc',(SELECT jsonb_build_object('invoker',NOT p.prosecdef,'authenticated_execute',has_function_privilege('authenticated',p.oid,'EXECUTE'),
  'anon_execute',has_function_privilege('anon',p.oid,'EXECUTE'),'definition_sha256',encode(sha256(convert_to(replace(pg_get_functiondef(p.oid),E'\r\n',E'\n'),'UTF8')),'hex'))
  FROM pg_proc p WHERE p.oid='public.aura_mutate_playlist_items(text,text,text,jsonb,timestamptz)'::regprocedure)
);
ROLLBACK;
