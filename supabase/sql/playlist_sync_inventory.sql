-- Read-only inventory. Supply the approved account UUID with psql -v owner_id=...
BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;

WITH occurrences AS (
    SELECT i.playlist_id, i.id, i.track_id, i.position,
           row_number() OVER (PARTITION BY i.playlist_id ORDER BY i.position, i.id) - 1 AS expected_position
    FROM public.playlist_items i
    JOIN public.playlists p ON p.id = i.playlist_id
    WHERE p.user_id = :'owner_id'::uuid
)
SELECT p.id, count(o.id) AS occurrences,
       count(o.id) - count(DISTINCT o.track_id) AS repeated_occurrences,
       count(*) FILTER (WHERE o.position <> o.expected_position) AS non_contiguous_positions,
       count(*) FILTER (WHERE btrim(o.track_id) = '') AS empty_track_references,
       count(*) FILTER (WHERE btrim(o.id) = '') AS empty_occurrence_ids
FROM public.playlists p
LEFT JOIN occurrences o ON o.playlist_id = p.id
WHERE p.user_id = :'owner_id'::uuid
GROUP BY p.id
ORDER BY p.id;

COMMIT;
