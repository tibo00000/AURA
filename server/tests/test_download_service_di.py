"""
Unit tests for DownloadService dependency injection and startup recovery configuration.
"""

import sys
import unittest
import asyncio
from unittest.mock import MagicMock

# Mock rapidfuzz and ytmusicapi if not installed in current environment
for mod in ("ytmusicapi", "rapidfuzz", "rapidfuzz.fuzz"):
    if mod not in sys.modules:
        sys.modules[mod] = MagicMock()

from app.config import Settings
from app.services.download_service import DownloadService


class TestDownloadServiceDependencyInjection(unittest.TestCase):
    def setUp(self):
        self.mock_supabase = MagicMock()

    def test_default_init_does_not_call_supabase(self):
        """Creating DownloadService should NOT make any network/Supabase calls."""
        service = DownloadService(supabase_client=self.mock_supabase, auto_recover_stale_jobs=False)
        self.mock_supabase.table.assert_not_called()

    def test_supabase_property_uses_injected_client(self):
        """Property .supabase returns the injected client."""
        service = DownloadService(supabase_client=self.mock_supabase)
        self.assertIs(service.supabase, self.mock_supabase)

    def test_recover_stale_jobs_when_enabled(self):
        """When enable_startup_recovery=True and app_env!='test', sweep updates stale jobs."""
        service = DownloadService(supabase_client=self.mock_supabase, auto_recover_stale_jobs=False)
        service.settings = Settings(
            enable_startup_recovery=True,
            app_env="production",
        )

        mock_table = MagicMock()
        mock_update = MagicMock()
        mock_in = MagicMock()

        self.mock_supabase.table.return_value = mock_table
        mock_table.update.return_value = mock_update
        mock_update.in_.return_value = mock_in

        service._recover_stale_jobs_on_startup()

        self.mock_supabase.table.assert_called_with("download_jobs")
        mock_table.update.assert_called_once()
        update_arg = mock_table.update.call_args[0][0]
        self.assertEqual(update_arg["status"], "failed")
        self.assertEqual(update_arg["error_code"], "server_restarted")
        mock_update.in_.assert_called_once_with("status", ["running", "queued"])
        mock_in.execute.assert_called_once()

    def test_recover_stale_jobs_skipped_when_disabled(self):
        """When enable_startup_recovery=False, recovery sweep is completely skipped."""
        service = DownloadService(supabase_client=self.mock_supabase, auto_recover_stale_jobs=False)
        service.settings = Settings(
            enable_startup_recovery=False,
            app_env="production",
        )

        service._recover_stale_jobs_on_startup()
        self.mock_supabase.table.assert_not_called()

    def test_recover_stale_jobs_skipped_in_test_env(self):
        """When app_env='test', recovery sweep is skipped even if enable_startup_recovery=True."""
        service = DownloadService(supabase_client=self.mock_supabase, auto_recover_stale_jobs=False)
        service.settings = Settings(
            enable_startup_recovery=True,
            app_env="test",
        )

        service._recover_stale_jobs_on_startup()
        self.mock_supabase.table.assert_not_called()

    def test_cleanup_orphaned_job_files_uses_injected_client(self):
        from app.domain.models import DownloadJob
        query = MagicMock()
        query.select.return_value = query
        query.eq.return_value = query
        query.in_.return_value = query
        query.execute.return_value = MagicMock(data=[])
        self.mock_supabase.table.return_value = query
        service = DownloadService(supabase_client=self.mock_supabase)
        keep = DownloadJob(id='job_active_123', user_id='synthetic-owner', track_id='trk_dummy_123',
                           provider_name='youtube', status='succeeded')
        asyncio.run(service._cleanup_orphaned_job_files(keep))
        self.mock_supabase.table.assert_called_with('download_jobs')
        self.assertIn(unittest.mock.call('user_id', 'synthetic-owner'), query.eq.call_args_list)
        self.assertIn(unittest.mock.call('track_id', 'trk_dummy_123'), query.eq.call_args_list)


if __name__ == "__main__":
    unittest.main()
