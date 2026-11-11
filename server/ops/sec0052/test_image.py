"""Run only embedded tests, returning machine-readable JUnit without a bind mount."""
import base64
from pathlib import Path
import sys

import pytest

targeted = ['test_auth_security.py','test_operational_logging.py','test_user_data_isolation.py',
    'test_playlist_rest_contract.py','test_download_delete_contract.py','test_download_job_security.py',
    'test_download_admission_recovery.py','test_sec0032_regressions.py','test_sec0033_robustness.py',
    'test_download_cache_provenance.py','test_reassign_audio.py','test_global_cache.py',
    'test_sec005_receive.py','test_sec005_storage.py','test_sec005_reassign_resume.py']
folder=Path('/opt/aura-validation/tests')
targets=[str(folder)] if sys.argv[1]=='full' else [str(folder/name) for name in targeted]
xml=Path('/tmp/sec0052-results.xml')
result=pytest.main(['-p','no:cacheprovider','-q','--tb=short','--junitxml='+str(xml),*targets])
if xml.exists():print('AURA_JUNIT_BASE64='+base64.b64encode(xml.read_bytes()).decode())
raise SystemExit(int(result))
