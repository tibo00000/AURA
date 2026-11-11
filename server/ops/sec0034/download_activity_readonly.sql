-- PREPARED ONLY. Target neosgkuccazvgkzwzjzh / postgres; separate approval required.
-- Metadata booleans and global counts, no identifiers, track names, URLs or contents.
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '5s';
SET LOCAL lock_timeout = '1s';
SELECT current_setting('transaction_read_only') = 'on' AS read_only,
       current_database() = 'postgres' AS database_matches,
       has_table_privilege(current_user, 'public.download_jobs', 'SELECT') AS can_select,
       (SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user)
         AS unfiltered_visibility;
-- Interpret counts only if ALL preceding booleans are true. Otherwise STOP.
SELECT count(*) AS total,
       count(*) FILTER (WHERE status = 'queued') AS queued,
       count(*) FILTER (WHERE status = 'running') AS running,
       count(*) FILTER (WHERE status = 'requires_resolution') AS requires_resolution,
       count(*) FILTER (WHERE status NOT IN
         ('queued','running','requires_resolution','succeeded','failed','cancelled','superseded')
         OR status IS NULL) AS unknown_status
FROM public.download_jobs
WHERE (SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user);
ROLLBACK;
