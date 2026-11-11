"""Linux fixture tests for the host-side receipt protocol; no Windows store read."""
import importlib.util
import io
import json
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import uuid

spec = importlib.util.spec_from_file_location("operator", Path(__file__).with_name("windows-session.py"))
operator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(operator)


class ReceiptProtocol(unittest.TestCase):
    def execute(self, failure):
        nonce = uuid.uuid4().hex
        user = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        payload = {"fixture_id": nonce, "user_id": user, "access_token": "SYNTHETIC_TOKEN"}
        journal = Path("/root/aura-sec0015-fixtures") / (nonce + ".jsonl")
        def command(args, **kwargs):
            if args[-1] == "pass":
                return SimpleNamespace(returncode=1 if failure else 0,
                                       stdout=json.dumps({"synthetic_files_removed": 2}).encode())
            return SimpleNamespace(returncode=0, stdout=b"")
        source = operator.TARGET_OUTER.replace("__IDENTITIES__", repr({operator.IMAGE_ID})).replace("__INNER__", repr("pass"))
        info = json.dumps([{"Image": operator.IMAGE_ID, "Config": {"Cmd": ["--factory", "/app/app/core/security-logging.json"]}}]).encode()
        with patch("sys.stdin", SimpleNamespace(buffer=io.BytesIO(json.dumps(payload).encode()))), patch(
            "sys.stdout", SimpleNamespace(buffer=io.BytesIO())
        ), patch("subprocess.check_output", return_value=info), patch("subprocess.run", side_effect=command):
            with self.assertRaises(SystemExit) as stopped:
                exec(compile(source, "receipt-protocol", "exec"), {})
        self.assertEqual(stopped.exception.code, 1 if failure else 0)
        content = journal.read_text()
        self.assertNotIn("SYNTHETIC_TOKEN", content)
        self.assertNotIn(user, content)
        rows = [json.loads(line) for line in content.splitlines()]
        self.assertEqual(len(rows[0]["planned_paths"]), 2)
        self.assertEqual(rows[1]["cleanup_confirmed"], not failure)
        journal.unlink()

    def test_success_has_receipt_without_credentials(self):
        self.execute(False)

    def test_failure_keeps_exact_receipt_for_manual_inventory(self):
        self.execute(True)

    def test_old_image_is_rejected_before_any_exec_or_fixture(self):
        source = operator.TARGET_OUTER.replace("__IDENTITIES__", repr({operator.IMAGE_ID})).replace("__INNER__", repr("pass"))
        info = json.dumps([{"Image": "sha256:old-vulnerable-image", "Config": {"Cmd": []}}]).encode()
        with patch("subprocess.check_output", return_value=info), patch("subprocess.run") as execute:
            with self.assertRaises(AssertionError):
                exec(compile(source, "old-image-guard", "exec"), {})
            execute.assert_not_called()


if __name__ == "__main__":
    unittest.main()
