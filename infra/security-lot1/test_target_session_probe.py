import contextlib
import hashlib
import importlib.util
import io
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import httpx
import wave

spec = importlib.util.spec_from_file_location("target", Path(__file__).with_name("target-session-probe.py"))
target = importlib.util.module_from_spec(spec)
spec.loader.exec_module(target)


class TargetProbe(unittest.TestCase):
    def execute(self, fail=False, bad_identity=False):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            unrelated = base / "unrelated.audio"
            unrelated.write_bytes(b"preserve")
            user = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
            payload = dict(hashes={n: hashlib.sha256(Path(n).read_bytes()).hexdigest() for n in target.FILES},
                           expected_origin="https://auth.example.test", access_token="synthetic-token", user_id=user, route="internal",
                           fixture_id="cccccccc-cccc-4ccc-8ccc-cccccccccccc")
            def transport(request):
                if request.url.path == "/health":
                    return httpx.Response(503 if fail else 200)
                if request.url.path.startswith("/test"):
                    return httpx.Response(404)
                if request.url.params.get("token") == "SYNTHETIC_TARGET_REFUSAL":
                    return httpx.Response(401)
                folder = base / hashlib.sha256(user.encode()).hexdigest()
                track = request.url.path.split("/")[-1]
                data = (folder / (hashlib.sha256(track.encode()).hexdigest() + ".audio")).read_bytes()
                return httpx.Response(206 if "range" in request.headers else 200, content=data[:32] if "range" in request.headers else data)
            def factory(**kwargs):
                return httpx.Client(**kwargs, transport=httpx.MockTransport(transport))
            settings = SimpleNamespace(app_debug=False, supabase_url=payload["expected_origin"], sync_files_dir=directory)
            with patch.object(target, "get_settings", return_value=settings), patch.object(target, "_decode_jwt_payload", return_value={"sub": "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb" if bad_identity else user}):
                if fail or bad_identity:
                    with self.assertRaises(RuntimeError):
                        target.run(payload, client_factory=factory, expected_base=base)
                else:
                    result = target.run(payload, client_factory=factory, expected_base=base)
                    self.assertEqual(result["synthetic_files_removed"], 2)
                    self.assertEqual(result["target_range"], 206)
            self.assertEqual(list(base.iterdir()), [unrelated])
            self.assertEqual(unrelated.read_bytes(), b"preserve")

    def test_success_and_exact_cleanup(self):
        self.execute()

    def test_http_failure_still_cleans_only_fixture(self):
        self.execute(fail=True)

    def test_wrong_identity_creates_nothing(self):
        self.execute(bad_identity=True)


if __name__ == "__main__":
    unittest.main()
