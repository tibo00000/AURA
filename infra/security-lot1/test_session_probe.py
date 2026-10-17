"""Offline tests only: generated signing keys and fake Supabase HTTP responses."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from cryptography.hazmat.primitives.asymmetric import ec
import httpx
import jwt

spec = importlib.util.spec_from_file_location("session_probe", Path(__file__).with_name("session-probe.py"))
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


class SessionProbe(unittest.TestCase):
    def execute(self, wait=False, status=200, algorithm="ES256"):
        key = ec.generate_private_key(ec.SECP256R1())
        subject = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        credentials = dict(url="https://auth.example.test", expected_origin="https://auth.example.test",
                           public_key="SYNTHETIC_PUBLIC", email="SYNTHETIC_EMAIL", password="SYNTHETIC_PASSWORD", user_id=subject)
        requests = []
        def response(request):
            requests.append(request)
            if status != 200:
                return httpx.Response(status, json={"error": "SYNTHETIC_PASSWORD"})
            if request.url.path.endswith("/logout"):
                return httpx.Response(204)
            grant = request.url.params["grant_type"]
            expiry = int(time.time()) + (15 if wait and grant == "password" else 60)
            token = jwt.encode(dict(sub=subject, iss=credentials["url"] + "/auth/v1", aud="authenticated",
                                    role="authenticated", exp=expiry), key if algorithm == "ES256" else "synthetic-hs-key" * 4,
                               algorithm=algorithm, headers={"kid": "synthetic"})
            return httpx.Response(200, json=dict(access_token=token, refresh_token="SYNTHETIC_REFRESH", user={"id": subject}))
        real_client = httpx.Client
        def client(*args, **kwargs):
            return real_client(*args, **kwargs, transport=httpx.MockTransport(response))
        fake_jwks = SimpleNamespace(get_signing_key=lambda kid: SimpleNamespace(algorithm_name="ES256", key=key.public_key()))
        output = io.StringIO()
        with patch.object(probe.httpx, "Client", side_effect=client), patch("app.core.auth._jwks_client", return_value=fake_jwks), contextlib.redirect_stdout(output):
            if status != 200 or algorithm == "HS256":
                with self.assertRaises(RuntimeError):
                    probe.run(credentials, wait)
                result = None
            else:
                result = probe.run(credentials, wait)
        text = output.getvalue()
        self.assertNotIn("SYNTHETIC_", text)
        self.assertNotIn(subject, text)
        self.assertNotIn("eyJ", text)
        return result, requests

    def test_complete_login_refresh_and_real_crypto_isolated_streaming(self):
        result, requests = self.execute()
        self.assertEqual(result["refresh"], 200)
        self.assertEqual(result["isolated_range"], 206)
        self.assertEqual(result["expiration"], "pending_real_expiry")
        self.assertEqual(result["local_logout"], 204)
        self.assertEqual(len(requests), 3)

    def test_wait_for_unmodified_token_expiry(self):
        result, _ = self.execute(wait=True)
        self.assertEqual(result["expiration"], 401)

    def test_http_auth_failure_is_sanitized(self):
        _, requests = self.execute(status=400)
        self.assertEqual(len(requests), 1)

    def test_redirect_is_rejected(self):
        _, requests = self.execute(status=302)
        self.assertEqual(len(requests), 1)

    def test_hs256_without_secret_stops_and_logs_out(self):
        _, requests = self.execute(algorithm="HS256")
        self.assertTrue(requests[-1].url.path.endswith("/logout"))


if __name__ == "__main__":
    unittest.main()
