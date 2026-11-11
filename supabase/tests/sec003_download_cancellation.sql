-- Local disposable PostgreSQL only: test_user_isolation_postgres.py --download-cancellation.
-- SQL semantics/CAS/rollback; real SDK query wiring is covered separately in pytest.
BEGIN;
CREATE TEMP TABLE sec003_results(label text PRIMARY KEY);
GRANT INSERT, SELECT ON sec003_results TO service_role;
CREATE FUNCTION pg_temp.check_sec003(ok boolean, label text) RETURNS void
LANGUAGE plpgsql SECURITY INVOKER AS $$
BEGIN
    IF ok IS DISTINCT FROM true THEN RAISE EXCEPTION 'SEC003 assertion failed: %', label; END IF;
    INSERT INTO pg_temp.sec003_results VALUES(label);
END $$;

INSERT INTO public.profiles(id) VALUES
('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'), ('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb');
INSERT INTO public.download_jobs(id,user_id,track_id,provider_name,status,attempt_count) VALUES
('job_a','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa','trk_synthetic','youtube','queued',1),
('job_b','bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb','trk_synthetic','youtube','running',1),
('job_atomic','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa','trk_synthetic','youtube','queued',1);

SET LOCAL ROLE service_role;
WITH changed AS (
 DELETE FROM public.download_jobs WHERE id='job_b' AND user_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
 AND status='running' AND attempt_count=1 RETURNING *
) SELECT pg_temp.check_sec003((SELECT count(*) FROM changed)=0,'foreign owner cannot delete');
SELECT pg_temp.check_sec003(EXISTS(SELECT 1 FROM public.download_jobs WHERE id='job_b' AND status='running'),'foreign row retained');

UPDATE public.download_jobs SET status='succeeded' WHERE id='job_a';
WITH changed AS (
 DELETE FROM public.download_jobs WHERE id='job_a' AND user_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
 AND status='queued' AND attempt_count=1 RETURNING *
) SELECT pg_temp.check_sec003((SELECT count(*) FROM changed)=0,'state changed since authorization');

UPDATE public.download_jobs SET status='failed' WHERE id='job_a';
WITH changed AS (
 UPDATE public.download_jobs SET status='queued',attempt_count=2 WHERE id='job_a'
 AND user_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND status='failed' AND attempt_count=1 RETURNING *
) SELECT pg_temp.check_sec003((SELECT count(*) FROM changed)=1,'one restart CAS succeeds');
WITH changed AS (
 UPDATE public.download_jobs SET status='queued',attempt_count=2 WHERE id='job_a'
 AND user_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND status='failed' AND attempt_count=1 RETURNING *
) SELECT pg_temp.check_sec003((SELECT count(*) FROM changed)=0,'duplicate restart CAS refused');
WITH changed AS (
 UPDATE public.download_jobs SET progress_percent=99 WHERE id='job_a'
 AND user_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND status='queued' AND attempt_count=1 RETURNING *
) SELECT pg_temp.check_sec003((SELECT count(*) FROM changed)=0,'old attempt SQL cannot update newer count');

WITH changed AS (
 DELETE FROM public.download_jobs WHERE id='job_a' AND user_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
 AND status='queued' AND attempt_count=2 RETURNING *
) SELECT pg_temp.check_sec003((SELECT count(*) FROM changed)=1,'owned deletion returns one row');
WITH changed AS (
 UPDATE public.download_jobs SET status='succeeded' WHERE id='job_a'
 AND user_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND status='queued' AND attempt_count=2 RETURNING *
) SELECT pg_temp.check_sec003((SELECT count(*) FROM changed)=0,'deleted row is not resurrected');

RESET ROLE;
CREATE FUNCTION pg_temp.fail_sec003_delete() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER AS $$
BEGIN
    IF OLD.id='job_atomic' THEN RAISE EXCEPTION 'synthetic intermediate SQL failure'; END IF;
    RETURN OLD;
END $$;
CREATE TRIGGER sec003_fail AFTER DELETE ON public.download_jobs
FOR EACH ROW EXECUTE FUNCTION pg_temp.fail_sec003_delete();
SET LOCAL ROLE service_role;
DO $$
DECLARE rejected boolean := false;
BEGIN
    BEGIN
        DELETE FROM public.download_jobs WHERE id='job_atomic'
        AND user_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND status='queued' AND attempt_count=1;
    EXCEPTION WHEN raise_exception THEN rejected := true;
    END;
    PERFORM pg_temp.check_sec003(rejected,'intermediate SQL failure is visible');
END $$;
SELECT pg_temp.check_sec003(EXISTS(SELECT 1 FROM public.download_jobs WHERE id='job_atomic'),'failed delete transaction retains row');
RESET ROLE;
SELECT 'SEC003_SQL_PASSED='||count(*) FROM pg_temp.sec003_results;
ROLLBACK;
