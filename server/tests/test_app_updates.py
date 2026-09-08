import hashlib
import json
import os
import shutil
import tempfile
import unittest
from pathlib import Path

# Configure temp updates dir before app imports
TEST_TEMP_UPDATES = Path(tempfile.mkdtemp(prefix="aura_test_updates_"))
os.environ["UPDATES_DIR"] = str(TEST_TEMP_UPDATES)

import sys
from unittest.mock import MagicMock

for mod in ("yt_dlp", "ytmusicapi", "rapidfuzz", "rapidfuzz.fuzz"):
    if mod not in sys.modules:
        sys.modules[mod] = MagicMock()

from fastapi.testclient import TestClient
from app.main import app


class TestAppUpdates(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.client = TestClient(app)

    def setUp(self):
        # Clean up directory before each test
        for p in TEST_TEMP_UPDATES.iterdir():
            if p.is_file():
                p.unlink()
            elif p.is_dir():
                shutil.rmtree(p)

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(TEST_TEMP_UPDATES, ignore_errors=True)

    def test_get_version_default_when_no_file(self):
        """Si version.json n'existe pas, retourne une réponse par défaut propre."""
        response = self.client.get("/app/version")
        self.assertEqual(response.status_code, 200)
        json_data = response.json()
        self.assertIn("data", json_data)
        data = json_data["data"]
        self.assertEqual(data["version_code"], 1)
        self.assertEqual(data["version_name"], "0.1.0")
        self.assertEqual(data["min_supported_version"], 1)

    def test_get_version_with_explicit_sha256(self):
        """Lit correctement version.json avec son SHA-256 explicite."""
        version_data = {
            "version_code": 2,
            "version_name": "0.2.0",
            "download_url": "/app/updates/latest.apk",
            "sha256": "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890",
            "release_notes": "Tri dynamique et streaming instantané.",
            "min_supported_version": 1,
        }
        (TEST_TEMP_UPDATES / "version.json").write_text(json.dumps(version_data), encoding="utf-8")

        response = self.client.get("/app/version")
        self.assertEqual(response.status_code, 200)
        data = response.json()["data"]
        self.assertEqual(data["version_code"], 2)
        self.assertEqual(data["version_name"], "0.2.0")
        self.assertEqual(data["sha256"], version_data["sha256"])
        self.assertEqual(data["release_notes"], "Tri dynamique et streaming instantané.")
        self.assertEqual(data["min_supported_version"], 1)

    def test_get_version_auto_sha256_computation(self):
        """Calcule automatiquement le SHA-256 si sha256='auto' et que l'APK existe."""
        apk_content = b"Fake Android APK content for testing integrity"
        expected_sha = hashlib.sha256(apk_content).hexdigest()
        (TEST_TEMP_UPDATES / "latest.apk").write_bytes(apk_content)

        version_data = {
            "version_code": 3,
            "version_name": "0.3.0",
            "download_url": "/app/updates/latest.apk",
            "sha256": "auto",
            "release_notes": "Auto SHA test",
            "min_supported_version": 2,
        }
        (TEST_TEMP_UPDATES / "version.json").write_text(json.dumps(version_data), encoding="utf-8")

        response = self.client.get("/app/version")
        self.assertEqual(response.status_code, 200)
        data = response.json()["data"]
        self.assertEqual(data["version_code"], 3)
        self.assertEqual(data["sha256"], expected_sha)
        self.assertEqual(data["min_supported_version"], 2)

    def test_download_apk_nominal(self):
        """Télécharge l'APK avec le bon content-type et le bon contenu."""
        apk_content = b"PK\x03\x04...binary apk content..."
        (TEST_TEMP_UPDATES / "latest.apk").write_bytes(apk_content)

        response = self.client.get("/app/updates/latest.apk")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.headers["content-type"], "application/vnd.android.package-archive")
        self.assertEqual(response.content, apk_content)

    def test_download_apk_not_found(self):
        """Retourne 404 si le fichier n'existe pas."""
        response = self.client.get("/app/updates/non_existent.apk")
        self.assertEqual(response.status_code, 404)

    def test_download_apk_path_traversal_blocked(self):
        """Empêche toute tentative de path traversal."""
        response = self.client.get("/app/updates/../../etc/passwd")
        self.assertIn(response.status_code, (404, 400))


if __name__ == "__main__":
    unittest.main()
