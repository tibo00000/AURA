"""Offline generation of the reviewed migration inside an integrity-checked transaction.

Never executes SQL. Production execution requires approval naming both original migration
and this wrapper. Original bytes/hash remain unchanged; only outer BEGIN/COMMIT are replaced.
"""
if not __debug__:
    raise SystemExit('optimized_mode_refused')
import argparse
import hashlib
from pathlib import Path

ROOT=Path(__file__).resolve().parents[3]
SOURCE=ROOT/'supabase/migrations/20261017200822_secure_user_data_isolation.sql'
EXPECTED='ffa144f458f65cd2a0ba000ee07fbbeb6faa352cf9d6316f7c9e0b40a806afa4'
PREFIX=r"""-- SEC0024 generated wrapper. APPROVAL REQUIRED for production execution.
-- Exact reviewed source SHA256: ffa144f458f65cd2a0ba000ee07fbbeb6faa352cf9d6316f7c9e0b40a806afa4
BEGIN;
SET LOCAL lock_timeout='5s';
SET LOCAL statement_timeout='60s';
CREATE TEMP TABLE sec0024_before(relation text PRIMARY KEY, rows bigint, digest text) ON COMMIT DROP;
-- Serialize writers, including direct Data API clients, for stable before/after checks.
DO $snapshot$
DECLARE r record; n bigint; fingerprint text;
BEGIN
 FOR r IN SELECT c.oid,n.nspname,c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
  WHERE n.nspname='public' AND c.relkind IN ('r','p') ORDER BY c.oid LOOP
  EXECUTE format('LOCK TABLE %I.%I IN SHARE ROW EXCLUSIVE MODE',r.nspname,r.relname);
 END LOOP;
 FOR r IN SELECT n.nspname,c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
  WHERE n.nspname='public' AND c.relkind IN ('r','p') ORDER BY c.oid LOOP
  EXECUTE format($query$SELECT count(*), encode(sha256(convert_to(coalesce(string_agg(h,'' ORDER BY h),''),'UTF8')),'hex')
   FROM (SELECT encode(sha256(convert_to(to_jsonb(t)::text,'UTF8')),'hex') h FROM %I.%I t) data$query$,r.nspname,r.relname)
   INTO n,fingerprint;
  INSERT INTO sec0024_before VALUES(r.nspname||'.'||r.relname,n,fingerprint);
 END LOOP;
END $snapshot$;
-- BEGIN exact migration body (outer BEGIN/COMMIT omitted, all DDL/DML preserved).
"""
SUFFIX=r"""
-- END exact migration body.
DO $verify$
DECLARE r record; n bigint; fingerprint text;
BEGIN
 FOR r IN SELECT * FROM sec0024_before LOOP
  EXECUTE format($query$SELECT count(*), encode(sha256(convert_to(coalesce(string_agg(h,'' ORDER BY h),''),'UTF8')),'hex')
   FROM (SELECT encode(sha256(convert_to(to_jsonb(t)::text,'UTF8')),'hex') h FROM %s t) data$query$,r.relation::regclass)
   INTO n,fingerprint;
  IF n IS DISTINCT FROM r.rows OR fingerprint IS DISTINCT FROM r.digest THEN
   RAISE EXCEPTION 'SEC0024 existing data changed in %; transaction aborted',r.relation;
  END IF;
 END LOOP;
 IF NOT EXISTS(SELECT 1 FROM sec0024_before WHERE relation='public.processed_batches')
    AND EXISTS(SELECT 1 FROM public.processed_batches) THEN
  RAISE EXCEPTION 'SEC0024 unexpected rows in new marker table';
 END IF;
 IF (SELECT count(*) FROM pg_policy p JOIN pg_class c ON c.oid=p.polrelid JOIN pg_namespace n ON n.oid=c.relnamespace
     WHERE n.nspname='public' AND p.polname='sec002_owner_guard' AND NOT p.polpermissive AND c.relrowsecurity) < 11 THEN
  RAISE EXCEPTION 'SEC0024 restrictive RLS verification failed';
 END IF;
 IF EXISTS(SELECT 1 FROM pg_constraint WHERE conname IN ('sec002_event_session_owner_fk','sec002_history_session_owner_fk') AND NOT convalidated)
 OR (SELECT count(*) FROM pg_constraint WHERE conname IN ('sec002_event_session_owner_fk','sec002_history_session_owner_fk')) <> 2 THEN
  RAISE EXCEPTION 'SEC0024 composite FK verification failed';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM pg_proc p WHERE p.oid='public.aura_mutate_playlist_items(text,text,text,jsonb,timestamptz)'::regprocedure
  AND NOT p.prosecdef AND has_function_privilege('authenticated',p.oid,'EXECUTE') AND NOT has_function_privilege('anon',p.oid,'EXECUTE')) THEN
  RAISE EXCEPTION 'SEC0024 RPC verification failed';
 END IF;
END $verify$;
SELECT jsonb_build_object('existing_tables_unchanged',count(*),'integrity','SHA256 canonical row hashes, same transaction') FROM sec0024_before;
COMMIT;
"""

def guarded_sql():
 raw=SOURCE.read_bytes();assert hashlib.sha256(raw).hexdigest()==EXPECTED, 'Reviewed migration changed'
 lines=raw.decode('utf-8').replace('\r\n','\n').splitlines(keepends=True)
 start=next(i for i,line in enumerate(lines) if line.strip()=='BEGIN;')
 end=len(lines)-1
 while not lines[end].strip():end-=1
 assert lines[end].strip()=='COMMIT;'
 return PREFIX+''.join(lines[start+1:end])+SUFFIX

if __name__=='__main__':
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',required=True,type=Path);a=p.parse_args()
 with a.output.open('x',encoding='utf-8',newline='\n') as f:f.write(guarded_sql())
 print('Guarded SQL prepared offline; no database accessed.')
