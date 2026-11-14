BEGIN;

ALTER TABLE public.processed_operations ADD COLUMN playlist_revision bigint;

CREATE FUNCTION public.aura_apply_playlist_operation(
    p_operation_id text,
    p_entity_type text,
    p_entity_id text,
    p_operation_type text,
    p_payload jsonb
) RETURNS jsonb
LANGUAGE plpgsql SECURITY INVOKER SET search_path = ''
AS $function$
DECLARE
    owner_id uuid := auth.uid();
    parent public.playlists%ROWTYPE;
    target_playlist text;
    moving text;
    anchor text;
    side text;
    positions jsonb;
    append_position integer;
    result jsonb;
    existing_item public.playlist_items%ROWTYPE;
    commit_revision bigint;
    operation_status text := 'applied';
BEGIN
    IF owner_id IS NULL OR COALESCE(p_operation_id, '') = '' OR COALESCE(p_entity_id, '') = ''
        OR jsonb_typeof(p_payload) IS DISTINCT FROM 'object' THEN
        RAISE EXCEPTION 'Invalid playlist operation';
    END IF;
    target_playlist := CASE WHEN p_entity_type = 'playlist_item'
        THEN p_payload->>'playlist_id' ELSE p_entity_id END;

    BEGIN
        INSERT INTO public.processed_operations(user_id, operation_id)
        VALUES(owner_id, p_operation_id)
        ON CONFLICT (user_id, operation_id) DO NOTHING;
        IF NOT FOUND THEN
            SELECT COALESCE(op.playlist_revision, rev.revision, 0) INTO commit_revision
            FROM public.processed_operations op
            LEFT JOIN public.playlist_sync_revisions rev USING (user_id)
            WHERE op.user_id = owner_id AND op.operation_id = p_operation_id;
            RETURN jsonb_build_object('status', 'ignored_duplicate', 'playlist_revision', commit_revision);
        END IF;

        IF p_entity_type = 'playlist' AND p_operation_type = 'create' THEN
            IF jsonb_typeof(p_payload->'name') IS DISTINCT FROM 'string' THEN
                RAISE EXCEPTION 'Playlist name is required';
            END IF;
            INSERT INTO public.playlists(id, user_id, name, cover_uri, is_pinned)
            VALUES(target_playlist, owner_id, p_payload->>'name', p_payload->>'cover_uri',
                   COALESCE((p_payload->>'is_pinned')::boolean, false))
            ON CONFLICT (id) DO NOTHING;
            IF NOT FOUND AND NOT EXISTS (
                SELECT 1 FROM public.playlists WHERE id = target_playlist AND user_id = owner_id
            ) THEN
                RAISE EXCEPTION USING ERRCODE = 'P0409';
            END IF;
        ELSE
            SELECT * INTO parent FROM public.playlists
            WHERE id = target_playlist AND user_id = owner_id FOR UPDATE;
            IF NOT FOUND THEN
                IF p_entity_type = 'playlist' AND p_operation_type = 'delete' THEN
                    operation_status := 'ignored_duplicate';
                ELSE
                    RAISE EXCEPTION USING ERRCODE = 'P0409';
                END IF;
            END IF;

            IF p_entity_type = 'playlist' AND p_operation_type = 'update' THEN
                PERFORM public.aura_mutate_playlist_items(target_playlist, NULL, 'rest_patch', p_payload);
            ELSIF p_entity_type = 'playlist' AND p_operation_type = 'delete' THEN
                DELETE FROM public.playlists WHERE id = target_playlist AND user_id = owner_id;
            ELSIF p_entity_type = 'playlist_item' AND p_operation_type = 'create' THEN
                SELECT * INTO existing_item FROM public.playlist_items WHERE id = p_entity_id;
                IF FOUND AND (existing_item.playlist_id IS DISTINCT FROM target_playlist
                               OR existing_item.track_id IS DISTINCT FROM p_payload->>'track_id') THEN
                    RAISE EXCEPTION USING ERRCODE = 'P0409';
                END IF;
                SELECT COALESCE(max(position), -1) + 1 INTO append_position
                FROM public.playlist_items WHERE playlist_id = target_playlist;
                result := public.aura_mutate_playlist_items(
                    target_playlist, p_entity_id, 'insert',
                    p_payload || jsonb_build_object('position', append_position)
                );
                operation_status := result->>'status';
            ELSIF p_entity_type = 'playlist_item' AND p_operation_type = 'delete' THEN
                result := public.aura_mutate_playlist_items(target_playlist, p_entity_id, 'delete', '{}');
                operation_status := result->>'status';
            ELSIF p_entity_type = 'playlist_reorder' AND p_operation_type = 'update' THEN
                IF p_payload ? 'base_order_token' AND NOT (p_payload ? 'item_id') THEN
                    IF p_payload->>'base_order_token' IS DISTINCT FROM
                        'ord_' || left(md5(to_jsonb(parent)->>'updated_at'), 8) THEN
                        RAISE EXCEPTION USING ERRCODE = 'P0409';
                    END IF;
                    positions := p_payload->'items';
                ELSE
                    moving := p_payload->>'item_id';
                    anchor := p_payload->>'anchor_id';
                    side := p_payload->>'side';
                    IF moving IS NULL OR anchor IS NULL OR moving = anchor
                        OR side IS NULL OR side NOT IN ('before', 'after') THEN
                        RAISE EXCEPTION 'Invalid playlist move';
                    END IF;
                    IF (SELECT count(*) FROM public.playlist_items
                        WHERE playlist_id = target_playlist AND id IN (moving, anchor)) <> 2 THEN
                        RAISE EXCEPTION USING ERRCODE = 'P0409';
                    END IF;
                    SELECT jsonb_agg(jsonb_build_object('playlist_item_id', id, 'position', new_position))
                    INTO positions
                    FROM (
                        SELECT item.id, row_number() OVER (
                            ORDER BY CASE WHEN item.id = moving THEN target.position ELSE item.position END,
                                     CASE WHEN item.id = moving AND side = 'before' THEN -1
                                          WHEN item.id = moving THEN 1 ELSE 0 END
                        ) - 1 AS new_position
                        FROM public.playlist_items item
                        CROSS JOIN public.playlist_items target
                        WHERE item.playlist_id = target_playlist AND target.playlist_id = target_playlist
                            AND target.id = anchor
                    ) ordered;
                END IF;
                result := public.aura_mutate_playlist_items(
                    target_playlist, NULL, 'reorder', jsonb_build_object('items', positions), parent.updated_at
                );
                IF result->>'status' <> 'applied' THEN
                    RAISE EXCEPTION USING ERRCODE = 'P0409';
                END IF;
            ELSE
                RAISE EXCEPTION 'Unsupported playlist operation';
            END IF;
        END IF;
        SELECT COALESCE((
            SELECT revision FROM public.playlist_sync_revisions WHERE user_id = owner_id
        ), 0) INTO commit_revision;
        UPDATE public.processed_operations SET playlist_revision = commit_revision
        WHERE user_id = owner_id AND operation_id = p_operation_id;
        RETURN jsonb_build_object('status', operation_status, 'playlist_revision', commit_revision);
    EXCEPTION WHEN SQLSTATE 'P0409' OR unique_violation THEN
        RETURN jsonb_build_object('status', 'conflict');
    END;
END;
$function$;

REVOKE ALL ON FUNCTION public.aura_apply_playlist_operation(text,text,text,text,jsonb)
    FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.aura_apply_playlist_operation(text,text,text,text,jsonb) TO authenticated;

COMMIT;
