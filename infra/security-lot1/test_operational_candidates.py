"""Linux/root fixture tests: fake firewall only, no network administration."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest

spec = importlib.util.spec_from_file_location("ports", Path(__file__).with_name("ports-temporary.py"))
ports = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ports)


class Firewall:
    def __init__(self, fail_insert=None, insert_then_fail=False):
        self.rules = {("unrelated",)}
        self.inserts = 0
        self.fail_insert = fail_insert
        self.insert_then_fail = insert_then_fail
        self.unavailable = None
        self.route = True

    def __call__(self, args, **kwargs):
        if args[0] == "ip":
            return SimpleNamespace(returncode=0, stdout="default via fixture dev eth0" if self.route else "")
        if args[0] == self.unavailable:
            return SimpleNamespace(returncode=2)
        operation = args[3]
        if operation == "-S":
            return SimpleNamespace(returncode=0)
        key = tuple([args[0], args[4]] + args[6:] if operation == "-I" else [args[0], args[4]] + args[5:])
        if operation == "-C":
            code = 0 if key in self.rules else 1
        elif operation == "-I":
            self.inserts += 1
            failing = self.inserts == self.fail_insert
            if not failing or self.insert_then_fail:
                self.rules.add(key)
            code = 2 if failing else 0
        elif operation == "-D":
            self.rules.remove(key)
            code = 0
        else:
            raise AssertionError(args)
        return SimpleNamespace(returncode=code)


class Candidates(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        os.chmod(self.directory.name, 0o700)
        self.path = Path(self.directory.name) / "ports.json"

    def execute(self, mode, fake):
        with contextlib.redirect_stdout(io.StringIO()):
            ports.Operation(self.path, fake).execute(mode)

    def test_all_six_partial_failures_before_and_after_kernel_insert(self):
        for position in range(1, 7):
            for inserted in (False, True):
                with self.subTest(position=position, inserted=inserted):
                    self.path.unlink(missing_ok=True)
                    fake = Firewall(position, inserted)
                    with self.assertRaises(RuntimeError):
                        self.execute("apply", fake)
                    self.assertEqual(json.loads(self.path.read_text())["attempted"], list(range(position)))
                    self.assertEqual(len(fake.rules), 1 + position - (not inserted))
                    self.execute("status", fake)
                    fake.route = False  # Removal does not depend on today's routing.
                    self.execute("remove", fake)
                    self.execute("remove", fake)
                    self.assertEqual(fake.rules, {("unrelated",)})

    def test_unavailable_ipv6_cleanup_continues_then_can_retry(self):
        fake = Firewall()
        self.execute("apply", fake)
        fake.unavailable = "ip6tables"
        with self.assertRaises(RuntimeError):
            self.execute("remove", fake)
        self.assertEqual(len(fake.rules), 4)
        fake.unavailable = None
        self.execute("remove", fake)
        self.assertEqual(fake.rules, {("unrelated",)})

    def test_preflight_failure_changes_nothing(self):
        fake = Firewall()
        fake.unavailable = "ip6tables"
        with self.assertRaises(RuntimeError):
            self.execute("apply", fake)
        self.assertEqual(fake.inserts, 0)

    def test_second_apply_refuses_existing_journal(self):
        fake = Firewall()
        self.execute("apply", fake)
        with self.assertRaises(FileExistsError):
            self.execute("apply", fake)
        self.assertEqual(fake.inserts, 6)

    def test_caddy_backup_and_restore_preserve_open_inode(self):
        target = Path(self.directory.name) / "Caddyfile"
        original, maintenance, release = b"original\n", b"maintenance\n", b"release\n"
        target.write_bytes(original)
        inode = target.stat().st_ino
        backup = target.read_bytes()
        with target.open("rb") as mounted:
            for content in (maintenance, release, maintenance, backup):
                with target.open("wb") as stream:
                    stream.write(content)
                    stream.flush()
                    os.fsync(stream.fileno())
                mounted.seek(0)
                self.assertEqual(mounted.read(), content)
                self.assertEqual(target.stat().st_ino, inode)


if __name__ == "__main__":
    unittest.main()
