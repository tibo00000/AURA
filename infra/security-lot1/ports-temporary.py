"""Scoped temporary rules; retain protection on failure and inventory before removal."""
import fcntl
from contextlib import ExitStack
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import tempfile
import uuid


def definitions(tag):
    rules = []
    for tool in ("iptables", "ip6tables"):
        common = ["-i", "eth0", "-p", "tcp"]
        tail = ["-m", "comment", "--comment", tag, "-j", "DROP"]
        rules.append([tool, "INPUT", common + ["-m", "multiport", "--dports", "8000,8001"] + tail])
        for port in ("8000", "8001"):
            rules.append([tool, "DOCKER-USER", common + ["-m", "conntrack", "--ctdir", "ORIGINAL", "--ctorigdstport", port] + tail])
    return rules


class Operation:
    def __init__(self, path, run=subprocess.run):
        self.path = Path(path)
        self.run = run

    def call(self, args):
        return self.run(args, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False).returncode

    def save(self):
        with tempfile.NamedTemporaryFile(mode="w", dir=self.path.parent, delete=False) as stream:
            temporary = stream.name
            json.dump(self.data, stream)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, self.path)
        directory = os.open(self.path.parent, os.O_DIRECTORY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)

    def present(self, rule):
        tool, chain, args = rule
        code = self.call([tool, "-w", "5", "-C", chain] + args)
        if code not in (0, 1):
            raise RuntimeError("Rule check failed; inventory is incomplete")
        return code == 0

    def execute(self, mode):
        parent = self.path.parent.stat()
        if not self.path.is_absolute() or parent.st_uid != 0 or stat.S_IMODE(parent.st_mode) != 0o700:
            raise RuntimeError("Use an absolute journal path in a root-owned 0700 directory")
        flags = os.O_RDWR | os.O_NOFOLLOW
        if mode == "apply":
            flags |= os.O_CREAT | os.O_EXCL
        with ExitStack() as stack:
            lock = stack.enter_context(os.fdopen(os.open(str(self.path) + ".lock", os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600), "r+"))
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            fd = os.open(self.path, flags, 0o600)
            self.file = stack.enter_context(os.fdopen(fd, "r+"))
            info = os.fstat(fd)
            if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or stat.S_IMODE(info.st_mode) != 0o600:
                raise RuntimeError("Journal must be a root-owned regular 0600 file")
            if mode == "apply":
                self.data = {"tag": "AURA_SEC0013_" + uuid.uuid4().hex, "attempted": []}
                self.save()
                for family in ([], ["-6"]):
                    result = self.run(["ip"] + family + ["route", "show", "default"], capture_output=True, text=True, check=False)
                    if result.returncode or " dev eth0" not in result.stdout:
                        raise RuntimeError("Routing differs from the approved eth0 topology")
                for tool in ("iptables", "ip6tables"):
                    for chain in ("INPUT", "DOCKER-USER"):
                        if self.call([tool, "-w", "5", "-S", chain]):
                            raise RuntimeError("Preflight chain unavailable")
                rules = definitions(self.data["tag"])
                if any(self.present(rule) for rule in rules):
                    raise RuntimeError("Unique operation tag collision")
                for index, (tool, chain, args) in enumerate(rules):
                    # Write-ahead intent covers failure after kernel insertion but before return.
                    self.data["attempted"].append(index)
                    self.save()
                    if self.call([tool, "-w", "5", "-I", chain, "1"] + args):
                        raise RuntimeError("Partial apply: keep rules; run status with this journal")
            else:
                self.data = json.load(self.file)
                if not isinstance(self.data["tag"], str) or not re.fullmatch(r"AURA_SEC0013_[0-9a-f]{32}", self.data["tag"]):
                    raise RuntimeError("Invalid operation journal")
                if any(type(i) is not int or i not in range(6) for i in self.data["attempted"]):
                    raise RuntimeError("Invalid rule index")
                rules = definitions(self.data["tag"])
            failed = False
            print("operation: " + self.data["tag"])
            for index, rule in enumerate(rules):
                tool, chain, args = rule
                label = f"{index} {tool} {chain} " + ("8000,8001" if chain == "INPUT" else args[args.index("--ctorigdstport") + 1])
                if index not in self.data["attempted"]:
                    print(f"rule {label}: not attempted")
                    continue
                try:
                    exists = self.present(rule)
                    if mode == "remove" and exists:
                        tool, chain, args = rule
                        if self.call([tool, "-w", "5", "-D", chain] + args) or self.present(rule):
                            raise RuntimeError("Removal not confirmed")
                        exists = False
                    print(f"rule {label}: {'present' if exists else 'absent'}")
                    if mode == "apply" and not exists:
                        failed = True
                except (RuntimeError, OSError):
                    print(f"rule {label}: unknown (retry inventory/removal)")
                    failed = True
            if failed:
                raise RuntimeError("Incomplete operation; keep journal and remaining protection")


if __name__ == "__main__":
    try:
        if len(sys.argv) != 3 or sys.argv[1] not in ("apply", "status", "remove") or os.geteuid() != 0:
            raise RuntimeError("Usage as root: ports-temporary.sh apply|status|remove /root/<operation>/ports.json")
        Operation(sys.argv[2]).execute(sys.argv[1])
    except (OSError, ValueError, KeyError, RuntimeError) as exc:
        print(f"STOP: {exc}", file=sys.stderr)
        sys.exit(1)
