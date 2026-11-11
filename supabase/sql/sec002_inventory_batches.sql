-- OPTIONAL THIRD STEP only if metadata confirms processed_batches exists with expected columns.
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout = '20s';
SET LOCAL lock_timeout = '2s';
SELECT jsonb_build_object('batch_orphan_owner',count(*) FILTER(WHERE p.id IS NULL),
 'batch_rows',count(*)) FROM public.processed_batches b LEFT JOIN public.profiles p ON p.id=b.user_id;
ROLLBACK;
