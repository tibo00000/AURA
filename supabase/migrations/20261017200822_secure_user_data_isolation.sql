-- SEC-002 / original audit finding 2. LOCAL PREPARATION ONLY; remote approval required.
-- PostgreSQL >=15. One transaction; no cleanup, ownership transfer or data deletion.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

-- The legacy RLS migration lived outside supabase/migrations; ensure this table exists.
CREATE TABLE IF NOT EXISTS public.processed_batches (
    batch_id text NOT NULL,
    user_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    processed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, batch_id)
);

-- Abort rather than silently rewriting legacy cross-account/orphan links.
DO $preflight$
DECLARE bad_events bigint; bad_history bigint; bad_markers bigint; unsafe_functions bigint; unsafe_triggers bigint; unsafe_roles bigint;
BEGIN
    SELECT count(*) INTO unsafe_roles FROM pg_catalog.pg_roles r
    WHERE r.rolname IN ('authenticated','anon') AND (
        r.rolsuper OR r.rolbypassrls OR pg_has_role(r.oid,'service_role','MEMBER')
        OR EXISTS(SELECT 1 FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
            WHERE n.nspname='public' AND c.relname IN ('profiles','user_settings','playlists','playlist_items',
                'likes','playback_snapshots','history_items','listening_sessions','playback_events',
                'processed_operations','processed_batches') AND pg_has_role(r.oid,c.relowner,'MEMBER'))
    );
    IF unsafe_roles > 0 THEN RAISE EXCEPTION 'SEC-002 unsafe client role privileges: %',unsafe_roles; END IF;
    SELECT count(*) INTO unsafe_functions FROM pg_catalog.pg_proc f
    JOIN pg_catalog.pg_namespace n ON n.oid=f.pronamespace
    WHERE n.nspname='public' AND f.prosecdef AND f.prorettype <> 'trigger'::regtype
      AND (has_function_privilege('authenticated',f.oid,'EXECUTE') OR has_function_privilege('anon',f.oid,'EXECUTE'));
    SELECT count(*) INTO unsafe_triggers FROM pg_catalog.pg_trigger t
    JOIN pg_catalog.pg_proc f ON f.oid=t.tgfoid
    WHERE NOT t.tgisinternal AND f.prosecdef AND t.tgrelid IN (
        'public.profiles'::regclass,'public.user_settings'::regclass,'public.playlists'::regclass,
        'public.playlist_items'::regclass,'public.likes'::regclass,'public.playback_snapshots'::regclass,
        'public.history_items'::regclass,'public.listening_sessions'::regclass,'public.playback_events'::regclass,
        'public.processed_operations'::regclass,'public.processed_batches'::regclass
    );
    IF unsafe_functions + unsafe_triggers > 0 THEN
        RAISE EXCEPTION 'SEC-002 privileged code requires review: functions %, triggers %',unsafe_functions,unsafe_triggers;
    END IF;
    SELECT count(*) INTO bad_events
    FROM public.playback_events e LEFT JOIN public.listening_sessions s ON s.id = e.session_id
    WHERE s.id IS NULL OR e.user_id IS DISTINCT FROM s.user_id;
    SELECT count(*) INTO bad_history
    FROM public.history_items h LEFT JOIN public.listening_sessions s ON s.id = h.listening_session_id
    WHERE h.listening_session_id IS NOT NULL AND (s.id IS NULL OR h.user_id IS DISTINCT FROM s.user_id);
    SELECT count(*) INTO bad_markers FROM (
        SELECT user_id FROM public.processed_operations
        UNION ALL SELECT user_id FROM public.processed_batches
    ) m LEFT JOIN public.profiles p ON p.id = m.user_id WHERE p.id IS NULL;
    IF bad_events + bad_history + bad_markers > 0 THEN
        RAISE EXCEPTION 'SEC-002 preflight failed: events %, history %, markers %; no automatic repair',
            bad_events, bad_history, bad_markers;
    END IF;
END
$preflight$;

-- Retain existing opaque IDs; only idempotency uniqueness becomes account-scoped.
DO $keys$
DECLARE t text; k text; pk text; columns text[];
BEGIN
    FOREACH t IN ARRAY ARRAY['processed_operations', 'processed_batches'] LOOP
        k := CASE t WHEN 'processed_operations' THEN 'operation_id' ELSE 'batch_id' END;
        SELECT c.conname, array_agg(a.attname::text ORDER BY u.ord) INTO pk, columns
        FROM pg_catalog.pg_constraint c
        CROSS JOIN LATERAL unnest(c.conkey) WITH ORDINALITY AS u(attnum, ord)
        JOIN pg_catalog.pg_attribute a ON a.attrelid=c.conrelid AND a.attnum=u.attnum
        WHERE c.conrelid=format('public.%I',t)::regclass AND c.contype='p'
        GROUP BY c.conname;
        IF columns IS DISTINCT FROM ARRAY['user_id', k] THEN
            IF pk IS NOT NULL THEN EXECUTE format('ALTER TABLE public.%I DROP CONSTRAINT %I',t,pk); END IF;
            -- No CASCADE: unexpected dependencies cause rollback and require review.
            EXECUTE format('ALTER TABLE public.%I ADD PRIMARY KEY (user_id,%I)',t,k);
        END IF;
    END LOOP;
END
$keys$;

-- The parent and child owner must agree even for a privileged server write.
ALTER TABLE public.listening_sessions ADD CONSTRAINT sec002_session_owner_key UNIQUE(user_id,id);
ALTER TABLE public.playback_events ADD CONSTRAINT sec002_event_session_owner_fk
    FOREIGN KEY(user_id,session_id) REFERENCES public.listening_sessions(user_id,id) ON DELETE CASCADE;
ALTER TABLE public.history_items ADD CONSTRAINT sec002_history_session_owner_fk
    FOREIGN KEY(user_id,listening_session_id) REFERENCES public.listening_sessions(user_id,id)
    ON DELETE SET NULL (listening_session_id);
CREATE INDEX IF NOT EXISTS sec002_event_session_owner_idx ON public.playback_events(user_id,session_id);
CREATE INDEX IF NOT EXISTS sec002_history_session_owner_idx ON public.history_items(user_id,listening_session_id);
CREATE INDEX IF NOT EXISTS sec002_playlists_owner_idx ON public.playlists(user_id,id);
CREATE INDEX IF NOT EXISTS sec002_items_parent_idx ON public.playlist_items(playlist_id,id);

-- Deferral allows a permutation/shift to preserve uniqueness at RPC commit, not halfway.
ALTER TABLE public.playlist_items DROP CONSTRAINT unique_playlist_position;
ALTER TABLE public.playlist_items ADD CONSTRAINT unique_playlist_position
    UNIQUE(playlist_id,position) DEFERRABLE INITIALLY IMMEDIATE;

-- Explicit allow policy + restrictive guard: unknown legacy permissive policies cannot widen it.
GRANT USAGE ON SCHEMA public TO authenticated;
DO $policies$
DECLARE t text; owner_predicate text;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'profiles','user_settings','playlists','playlist_items','likes','playback_snapshots',
        'history_items','listening_sessions','playback_events','processed_operations','processed_batches'
    ] LOOP
        owner_predicate := CASE t
            WHEN 'profiles' THEN '(select auth.uid()) = id'
            WHEN 'playlist_items' THEN 'EXISTS (SELECT 1 FROM public.playlists p WHERE p.id = playlist_items.playlist_id AND p.user_id = (select auth.uid()))'
            WHEN 'history_items' THEN 'user_id = (select auth.uid()) AND (listening_session_id IS NULL OR EXISTS (SELECT 1 FROM public.listening_sessions s WHERE s.id = history_items.listening_session_id AND s.user_id = (select auth.uid())))'
            WHEN 'playback_events' THEN 'user_id = (select auth.uid()) AND EXISTS (SELECT 1 FROM public.listening_sessions s WHERE s.id = playback_events.session_id AND s.user_id = (select auth.uid()))'
            ELSE '(select auth.uid()) = user_id' END;
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY',t);
        EXECUTE format('REVOKE ALL ON TABLE public.%I FROM PUBLIC,anon,authenticated',t);
        EXECUTE format('GRANT SELECT,INSERT,UPDATE,DELETE ON TABLE public.%I TO authenticated',t);
        EXECUTE format('DROP POLICY IF EXISTS sec002_owner_allow ON public.%I',t);
        EXECUTE format('DROP POLICY IF EXISTS sec002_owner_guard ON public.%I',t);
        EXECUTE format('CREATE POLICY sec002_owner_allow ON public.%I FOR ALL TO authenticated USING (%s) WITH CHECK (%s)',t,owner_predicate,owner_predicate);
        EXECUTE format('CREATE POLICY sec002_owner_guard ON public.%I AS RESTRICTIVE FOR ALL TO authenticated USING (%s) WITH CHECK (%s)',t,owner_predicate,owner_predicate);
    END LOOP;
END
$policies$;

-- Jobs are server-authoritative. No client in this repository accesses this table directly.
ALTER TABLE public.download_jobs ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.download_jobs FROM PUBLIC,anon,authenticated;

-- Optional documented tables have no current mutation path. Scope them if present.
DO $optional$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['user_track_stats','recent_searches'] LOOP
        IF to_regclass(format('public.%I',t)) IS NOT NULL THEN
            EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY',t);
            EXECUTE format('REVOKE ALL ON TABLE public.%I FROM PUBLIC,anon,authenticated',t);
            EXECUTE format('GRANT SELECT ON TABLE public.%I TO authenticated',t);
            EXECUTE format('CREATE POLICY sec002_owner_allow ON public.%I FOR SELECT TO authenticated USING ((select auth.uid())=user_id)',t);
            EXECUTE format('CREATE POLICY sec002_owner_guard ON public.%I AS RESTRICTIVE FOR ALL TO authenticated USING ((select auth.uid())=user_id) WITH CHECK ((select auth.uid())=user_id)',t);
        END IF;
    END LOOP;
END
$optional$;

CREATE FUNCTION public.aura_mutate_playlist_items(
    p_playlist_id text,
    p_item_id text,
    p_action text,
    p_payload jsonb,
    p_expected_updated_at timestamptz DEFAULT NULL
) RETURNS jsonb
LANGUAGE plpgsql SECURITY INVOKER SET search_path = ''
AS $function$
DECLARE
    owner uuid := auth.uid();
    parent public.playlists%ROWTYPE;
    existing public.playlist_items%ROWTYPE;
    inserted public.playlist_items%ROWTYPE;
    entries jsonb;
    entry jsonb;
    n integer;
    item_position integer;
    stamp timestamptz;
BEGIN
    IF p_action IS NULL OR p_action NOT IN ('reorder','insert','delete','rest_insert','rest_delete_track','rest_patch')
       OR p_payload IS NULL OR jsonb_typeof(p_payload) <> 'object' THEN
        RAISE EXCEPTION 'Invalid mutation';
    END IF;
    IF owner IS NULL THEN RAISE EXCEPTION 'Resource unavailable' USING ERRCODE='42501'; END IF;
    SELECT * INTO parent FROM public.playlists
        WHERE id=p_playlist_id AND user_id=owner FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Resource unavailable' USING ERRCODE='42501'; END IF;

    -- REST contracts differ from batch: never shift on insert, remove ALL track occurrences.
    -- Child DML, parent touch and returned snapshot share the same parent lock/transaction.
    IF p_action IN ('rest_insert','rest_delete_track','rest_patch') THEN
        IF p_action='rest_insert' THEN
            IF p_item_id IS NULL OR jsonb_typeof(p_payload->'track_id') IS DISTINCT FROM 'string'
               OR jsonb_typeof(p_payload->'position') IS DISTINCT FROM 'number'
               OR (p_payload->>'position') !~ '^-?[0-9]+$' THEN RAISE EXCEPTION 'Invalid item'; END IF;
            INSERT INTO public.playlist_items(id,playlist_id,track_id,position,added_at,added_from_context_type,added_from_context_id)
            VALUES(p_item_id,p_playlist_id,p_payload->>'track_id',(p_payload->>'position')::integer,
                clock_timestamp(),p_payload->>'added_from_context_type',p_payload->>'added_from_context_id')
            RETURNING * INTO inserted;
            -- Also detects a conflict if a caller has already deferred this constraint.
            SET CONSTRAINTS public.unique_playlist_position IMMEDIATE;
        ELSIF p_action='rest_delete_track' THEN
            IF p_item_id IS NULL THEN RAISE EXCEPTION 'Invalid track'; END IF;
            WITH removed AS (
                DELETE FROM public.playlist_items WHERE playlist_id=p_playlist_id AND track_id=p_item_id RETURNING *
            ) SELECT coalesce(jsonb_agg(to_jsonb(removed)),'[]'::jsonb) INTO entries FROM removed;
        ELSE
            IF (p_payload ? 'name' AND jsonb_typeof(p_payload->'name') NOT IN ('string','null'))
               OR (p_payload ? 'cover_uri' AND jsonb_typeof(p_payload->'cover_uri') NOT IN ('string','null'))
               OR (p_payload ? 'is_pinned' AND jsonb_typeof(p_payload->'is_pinned') NOT IN ('boolean','null'))
            THEN RAISE EXCEPTION 'Invalid metadata'; END IF;
        END IF;
        stamp := greatest(clock_timestamp(),parent.updated_at + interval '1 microsecond');
        UPDATE public.playlists SET updated_at=stamp,
            name=CASE WHEN p_action='rest_patch' THEN coalesce(p_payload->>'name',parent.name) ELSE parent.name END,
            cover_uri=CASE WHEN p_action='rest_patch' THEN coalesce(p_payload->>'cover_uri',parent.cover_uri) ELSE parent.cover_uri END,
            is_pinned=CASE WHEN p_action='rest_patch' THEN coalesce((p_payload->>'is_pinned')::boolean,parent.is_pinned) ELSE parent.is_pinned END
        WHERE id=p_playlist_id AND user_id=owner RETURNING * INTO parent;
        IF p_action='rest_patch' THEN
            SELECT coalesce(jsonb_agg(to_jsonb(i) ORDER BY i.position),'[]'::jsonb) INTO entries
                FROM public.playlist_items i WHERE i.playlist_id=p_playlist_id;
            RETURN jsonb_build_object('entity',to_jsonb(parent),'items',entries);
        ELSIF p_action='rest_insert' THEN
            RETURN jsonb_build_object('entity',to_jsonb(inserted),'updated_at',stamp);
        END IF;
        RETURN jsonb_build_object('entities',entries,'updated_at',stamp);
    END IF;

    IF p_action='reorder' THEN
        IF parent.updated_at IS DISTINCT FROM p_expected_updated_at THEN
            RETURN jsonb_build_object('status','conflict','updated_at',parent.updated_at);
        END IF;
        entries := COALESCE(p_payload->'items','[]'::jsonb);
        IF jsonb_typeof(entries) <> 'array' THEN RAISE EXCEPTION 'Invalid order'; END IF;
        n := jsonb_array_length(entries);
        -- Validate the COMPLETE request before any row is changed, including mixed/duplicate IDs.
        FOR entry IN SELECT value FROM jsonb_array_elements(entries) LOOP
            IF jsonb_typeof(entry->'playlist_item_id') <> 'string'
               OR (entry->>'playlist_item_id') IS NULL
               OR jsonb_typeof(entry->'position') <> 'number'
               OR (entry->>'position') !~ '^[0-9]+$' THEN RAISE EXCEPTION 'Invalid order'; END IF;
            item_position := (entry->>'position')::integer;
        END LOOP;
        IF (SELECT count(DISTINCT value->>'playlist_item_id') FROM jsonb_array_elements(entries)) <> n
           OR (SELECT count(DISTINCT (value->>'position')::integer) FROM jsonb_array_elements(entries)) <> n
           OR (SELECT count(*) FROM public.playlist_items i JOIN jsonb_array_elements(entries) e
                ON i.id=e.value->>'playlist_item_id' WHERE i.playlist_id=p_playlist_id) <> n
        THEN RAISE EXCEPTION 'Resource unavailable' USING ERRCODE='42501'; END IF;
        SET CONSTRAINTS public.unique_playlist_position DEFERRED;
        UPDATE public.playlist_items i SET position=(e.value->>'position')::integer
            FROM jsonb_array_elements(entries) e
            WHERE i.playlist_id=p_playlist_id AND i.id=e.value->>'playlist_item_id';
        SET CONSTRAINTS public.unique_playlist_position IMMEDIATE;
        stamp := greatest(clock_timestamp(),parent.updated_at + interval '1 microsecond');
        UPDATE public.playlists SET updated_at=stamp WHERE id=p_playlist_id AND user_id=owner;
        RETURN jsonb_build_object('status','applied','updated_at',stamp);
    END IF;

    IF p_item_id IS NULL OR p_item_id='' THEN RAISE EXCEPTION 'Invalid item'; END IF;
    SELECT * INTO existing FROM public.playlist_items WHERE id=p_item_id AND playlist_id=p_playlist_id;
    IF p_action='delete' THEN
        IF NOT FOUND THEN RETURN jsonb_build_object('status','ignored_duplicate','entity',NULL); END IF;
        DELETE FROM public.playlist_items WHERE id=p_item_id AND playlist_id=p_playlist_id;
        UPDATE public.playlists SET updated_at=greatest(clock_timestamp(),parent.updated_at + interval '1 microsecond')
            WHERE id=p_playlist_id AND user_id=owner;
        RETURN jsonb_build_object('status','applied','entity',NULL);
    ELSIF p_action <> 'insert' THEN
        RAISE EXCEPTION 'Invalid action';
    END IF;
    IF existing.id IS NOT NULL THEN
        RETURN jsonb_build_object('status','ignored_duplicate','entity',to_jsonb(existing));
    END IF;
    IF jsonb_typeof(p_payload->'track_id') <> 'string' OR COALESCE(p_payload->>'track_id','')='' THEN
        RAISE EXCEPTION 'Invalid track';
    END IF;
    IF p_payload ? 'position' AND (jsonb_typeof(p_payload->'position') <> 'number'
        OR (p_payload->>'position') !~ '^[0-9]+$') THEN RAISE EXCEPTION 'Invalid position'; END IF;
    item_position := COALESCE((p_payload->>'position')::integer,0);
    SET CONSTRAINTS public.unique_playlist_position DEFERRED;
    -- A hidden global item-ID collision rolls back this shift and the insert together.
    IF EXISTS(SELECT 1 FROM public.playlist_items WHERE playlist_id=p_playlist_id AND position=item_position) THEN
        UPDATE public.playlist_items SET position=position+1
            WHERE playlist_id=p_playlist_id AND position>=item_position;
    END IF;
    INSERT INTO public.playlist_items(id,playlist_id,track_id,position,added_at,added_from_context_type,added_from_context_id)
    VALUES(p_item_id,p_playlist_id,p_payload->>'track_id',item_position,
        COALESCE((p_payload->>'added_at')::timestamptz,clock_timestamp()),
        p_payload->>'added_from_context_type',p_payload->>'added_from_context_id') RETURNING * INTO inserted;
    SET CONSTRAINTS public.unique_playlist_position IMMEDIATE;
    UPDATE public.playlists SET updated_at=greatest(clock_timestamp(),parent.updated_at + interval '1 microsecond')
        WHERE id=p_playlist_id AND user_id=owner;
    RETURN jsonb_build_object('status','applied','entity',to_jsonb(inserted));
END
$function$;
REVOKE ALL ON FUNCTION public.aura_mutate_playlist_items(text,text,text,jsonb,timestamptz) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.aura_mutate_playlist_items(text,text,text,jsonb,timestamptz) TO authenticated;

COMMIT;
