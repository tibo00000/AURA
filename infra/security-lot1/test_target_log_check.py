import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("logcheck", Path(__file__).with_name("target-log-check.py"))
logcheck = importlib.util.module_from_spec(spec)
spec.loader.exec_module(logcheck)


class LogCheck(unittest.TestCase):
    def execute(self, leak=False, no_trace=False):
        request = {}
        def command(args, **kwargs):
            if args[1] == "exec":
                request.update(json.loads(kwargs["input"]))
                return SimpleNamespace(returncode=0)
            if no_trace:
                raw = b'{}\n'
            elif args[-1] == "aura-api":
                raw = json.dumps({"path": request["path"], "status": 404}).encode()
            else:
                raw = json.dumps({"request": {"uri": request["path"]}, "status": 503}).encode()
            if leak:
                raw += request["marker"].encode()
            return SimpleNamespace(returncode=0, stdout=raw, stderr=b"")
        with patch.object(logcheck.subprocess, "run", side_effect=command), patch.object(logcheck.time, "sleep"):
            if leak or no_trace:
                with self.assertRaises(RuntimeError):
                    logcheck.check()
            else:
                result = logcheck.check()
                self.assertTrue(result["aura-api"]["access_trace_preserved"])
                self.assertTrue(result["aura-caddy"]["synthetic_marker_absent"])

    def test_query_redacted_and_trace_retained(self):
        self.execute()

    def test_marker_leak_is_failure(self):
        self.execute(leak=True)

    def test_missing_trace_is_not_a_success(self):
        self.execute(no_trace=True)


if __name__ == "__main__":
    unittest.main()
