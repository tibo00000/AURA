"""Windows operator helper. No secrets in argv/output; DPAPI CurrentUser storage.

create-account is a separately authorized remote Auth write, never a VPS config change.
probe creates/refreshes/logs out only that account's sessions; all backend tests are local.
"""
import ctypes
from ctypes import wintypes
import json
import http.client
import os
from pathlib import Path
import secrets
import shlex
import subprocess
import sys
import uuid
from urllib.parse import urlsplit

IMAGE = "aura-security-lot1:prepared-20261017-amd64"
IMAGE_ID = "sha256:845680a8d0e8cba0ea37f52eb1254211ee9e9b4f5c4c89e547723a02a4065739"
ROOT = Path(__file__).resolve().parents[2]
STORE = ROOT / "scratch" / "security-lot1-credentials" / "session.dpapi"


class Blob(ctypes.Structure):
    _fields_ = [("size", wintypes.DWORD), ("data", ctypes.POINTER(ctypes.c_ubyte))]


def crypt(data, decrypt=False):
    if os.name != "nt":
        raise RuntimeError("windows_required")
    buffer = ctypes.create_string_buffer(data)
    source = Blob(len(data), ctypes.cast(buffer, ctypes.POINTER(ctypes.c_ubyte)))
    output = Blob()
    api = ctypes.windll.crypt32.CryptUnprotectData if decrypt else ctypes.windll.crypt32.CryptProtectData
    if not api(ctypes.byref(source), None, None, None, None, 1, ctypes.byref(output)):
        raise RuntimeError("dpapi_failed")
    try:
        return ctypes.string_at(output.data, output.size)
    finally:
        ctypes.windll.kernel32.LocalFree(output.data)


def save(data):
    STORE.parent.mkdir(parents=True, exist_ok=True)
    sid = subprocess.check_output(["powershell.exe", "-NoProfile", "-Command", "[System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value"], text=True).strip()
    subprocess.run(["icacls.exe", str(STORE.parent), "/inheritance:r", "/grant:r", "*" + sid + ":(OI)(CI)F", "*S-1-5-18:(OI)(CI)F"],
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
    encrypted = crypt(json.dumps(data).encode())
    temporary = STORE.with_suffix(".new")
    with temporary.open("wb") as stream:
        stream.write(encrypted)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, STORE)


REMOTE_INNER = r'''
import json,sys,os,logging
logging.disable(logging.CRITICAL)
import httpx
try:
 d=json.load(sys.stdin)
 url=os.environ['SUPABASE_URL'].rstrip('/')
 assert url==d['expected_origin'] and url.startswith('https://')
 if d['action']=='config':
  print(json.dumps({'url':url,'public_key':os.environ['SUPABASE_ANON_KEY']}))
 elif d['action']=='create':
  key=os.environ['SUPABASE_SERVICE_ROLE_KEY']
  with httpx.Client(timeout=15,follow_redirects=False,trust_env=False) as client:
   r=client.post(url+'/auth/v1/admin/users',headers={'Authorization':'Bearer '+key,'apikey':key},json={'email':d['email'],'password':d['password'],'email_confirm':True,'app_metadata':{'security_test':'SEC-001-lot1'}})
  if r.status_code not in (200,201):
   print(json.dumps({'created':False,'status':r.status_code}))
   sys.exit(1)
  user=r.json()
  if 'user' in user:user=user['user']
  assert user['email']==d['email']
  print(json.dumps({'created':True,'user_id':user['id']}))
 else:raise RuntimeError()
except BaseException:
 print(json.dumps({'remote_step_failed':True}))
 sys.exit(1)
'''
REMOTE_OUTER = "import subprocess,sys; p=subprocess.run(['docker','exec','-i','aura-api','python','-B','-c'," + repr(REMOTE_INNER) + "],input=sys.stdin.buffer.read(),capture_output=True); sys.stdout.buffer.write(p.stdout); sys.exit(p.returncode)"

TARGET_OUTER = r'''
import subprocess,sys,json,pathlib,hashlib,uuid,os,stat
d=json.loads(subprocess.check_output(['docker','inspect','aura-api']))[0]
assert d['Image'] in __IDENTITIES__
assert '--factory' in d['Config']['Cmd'] and '/app/app/core/security-logging.json' in d['Config']['Cmd']
payload=json.loads(sys.stdin.buffer.read())
nonce=uuid.UUID(payload['fixture_id']).hex
user=str(uuid.UUID(payload['user_id']))
folder=pathlib.Path('/app/downloads/sync_files')/hashlib.sha256(user.encode()).hexdigest()
key=hashlib.sha256(('security-lot1-'+nonce).encode()).hexdigest()
paths=[str(folder/(key+suffix)) for suffix in ['.audio','.json']]
# Stat only the exact test paths before recording intent; never list a library.
preflight="import pathlib,json,sys; paths=json.load(sys.stdin); assert all(not pathlib.Path(p).exists() and not pathlib.Path(p).is_symlink() for p in paths)"
p=subprocess.run(['docker','exec','-i','aura-api','python','-B','-c',preflight],input=json.dumps(paths).encode(),capture_output=True)
assert p.returncode==0
root=pathlib.Path('/root/aura-sec0015-fixtures')
root.mkdir(mode=0o700,exist_ok=True)
info=root.lstat()
assert stat.S_ISDIR(info.st_mode) and stat.S_IMODE(info.st_mode)==0o700 and info.st_uid==0
journal=root/(nonce+'.jsonl')
with os.fdopen(os.open(journal,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600),'w') as f:
 f.write(json.dumps({'fixture_id':nonce,'planned_paths':paths,'preflight_absent':True})+'\n');f.flush();os.fsync(f.fileno())
directory=os.open(root,os.O_DIRECTORY);os.fsync(directory);os.close(directory)
p=subprocess.run(['docker','exec','-i','aura-api','python','-B','-c',__INNER__],input=json.dumps(payload).encode(),capture_output=True)
with journal.open('a') as f:
 f.write(json.dumps({'process_exit':p.returncode,'cleanup_confirmed':p.returncode==0 and json.loads(p.stdout).get('synthetic_files_removed')==2})+'\n');f.flush();os.fsync(f.fileno())
sys.stdout.buffer.write(p.stdout)
sys.exit(p.returncode)
'''


def remote(payload, inner=None):
    identities = {IMAGE_ID, "sha256:55b614cd9839de05b6b3eabb5a1b5f7a52fde309be59a554b12bdadb4e984049", "sha256:7d7f18f5c403da6550195bb3ef6da86f21d6c8e2bc6db01a26b4c139bf72d22e"}
    outer = REMOTE_OUTER if inner is None else TARGET_OUTER.replace("__IDENTITIES__", repr(identities)).replace("__INNER__", repr(inner))
    command = ["ssh", "-T", "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes", "-o", "ConnectTimeout=10", "-o", "LogLevel=ERROR",
               "root@212.90.121.80", "python3 -B -c " + shlex.quote(outer)]
    p = subprocess.run(command, input=json.dumps(payload).encode(), capture_output=True, timeout=100)
    if p.returncode:
        raise RuntimeError("remote_step_failed_state_must_be_checked")
    return json.loads(p.stdout)


def auth_request(data, endpoint, body=None, token=None):
    origin = urlsplit(data["url"])
    if origin.scheme != "https" or data["url"] != data["expected_origin"]:
        raise RuntimeError("unexpected_origin")
    headers = {"apikey": data["public_key"], "Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    client = http.client.HTTPSConnection(origin.hostname, origin.port or 443, timeout=10)
    try:
        client.request("POST", "/auth/v1/" + endpoint, body=json.dumps(body or {}), headers=headers)
        response = client.getresponse()
        raw = response.read(1048576)
        if response.status not in (200, 204):
            raise RuntimeError("auth_call_failed")
        return json.loads(raw) if raw else {}
    finally:
        client.close()


def main():
    action = sys.argv[1]
    if action == "self-test":
        sample = secrets.token_bytes(64)
        assert crypt(crypt(sample), decrypt=True) == sample
        print(json.dumps({"dpapi_roundtrip": True}))
    elif action == "create-account":
        if STORE.exists():
            raise RuntimeError("credential_store_exists_no_duplicate_creation")
        origin = sys.argv[2]
        public = remote({"action": "config", "expected_origin": origin})
        data = dict(public, expected_origin=origin, email="security-lot1-" + uuid.uuid4().hex + "@aura.invalid",
                    password=secrets.token_urlsafe(48), creation_state="prepared")
        save(data)  # Recoverable local password even if the remote request is interrupted.
        created = remote(dict(data, action="create"))
        data.update(user_id=created["user_id"], creation_state="created")
        save(data)
        print(json.dumps({"test_account_created": True, "credential_store_dpapi": True, "secret_exported_from_vps": False}))
    elif action == "target-check":
        # Separate authorization required for the two synthetic files on the target.
        import hashlib
        data = json.loads(crypt(STORE.read_bytes(), decrypt=True))
        session = auth_request(data, "token?grant_type=password", {"email": data["email"], "password": data["password"]})
        access = session["access_token"]
        try:
            if session["user"]["id"] != data["user_id"] or sys.argv[2] not in ("internal", "public"):
                raise RuntimeError("unexpected_test_identity_or_route")
            names = ("app/core/auth.py", "app/api/router.py", "app/core/operational_logging.py", "app/core/security-logging.json")
            payload = {"expected_origin": data["expected_origin"], "access_token": access, "user_id": data["user_id"], "route": sys.argv[2], "fixture_id": str(uuid.uuid4()),
                       "hashes": {n: hashlib.sha256((ROOT / "server" / n).read_bytes()).hexdigest() for n in names}}
            result = remote(payload, inner=Path(__file__).with_name("target-session-probe.py").read_text(encoding="utf-8"))
        finally:
            auth_request(data, "logout?scope=local", token=access)
        print(json.dumps(result))
    elif action == "probe":
        data = json.loads(crypt(STORE.read_bytes(), decrypt=True))
        if data.get("creation_state") != "created":
            raise RuntimeError("creation_uncertain_check_exact_account_before_retry")
        actual = subprocess.check_output(["docker", "image", "inspect", IMAGE, "--format", "{{.Id}}"], text=True).strip()
        if actual != IMAGE_ID:
            raise RuntimeError("image_identity_mismatch")
        command = ["docker", "run", "--rm", "-i", "--platform", "linux/amd64", "--mount",
                   "type=bind,source=" + str(Path(__file__).parent) + ",target=/ops,readonly", "--entrypoint", "python", IMAGE,
                   "-B", "/ops/session-probe.py"]
        if "--wait-expiry" in sys.argv:
            command.append("--wait-expiry")
        with subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL) as process:
            process.stdin.write(json.dumps(data).encode())
            process.stdin.close()
            for line in process.stdout:
                # The reviewed probe emits only statuses, durations and allowlisted algorithms.
                print(json.dumps(json.loads(line)), flush=True)
            if process.wait():
                raise RuntimeError("probe_failed_details_redacted")
    else:
        raise RuntimeError("unknown_action")


if __name__ == "__main__":
    try:
        main()
    except BaseException:
        print(json.dumps({"operator_step_failed": True, "details_redacted": True}), flush=True)
        sys.exit(1)
