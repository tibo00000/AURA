"""Read-only operational check on VPS after corrected image/logging, synthetic traffic only.

Does not output raw logs. Does not simulate native provider failures or claim full coverage.
"""
from datetime import datetime, timezone
import json
import subprocess
import sys
import time
import uuid


def check():
    marker = "SYNTHETIC_SECRET_" + uuid.uuid4().hex
    path = "/__security_lot1_trace_" + uuid.uuid4().hex
    since = datetime.now(timezone.utc).isoformat()
    code = """
import sys,json,httpx
d=json.load(sys.stdin)
with httpx.Client(timeout=5,follow_redirects=False,trust_env=False) as c:
 for base in ['http://127.0.0.1:8000','https://aura-prod.duckdns.org']:
  r=c.get(base+d['path'],params={'token':d['marker']},headers={'Authorization':'Bearer '+d['marker'],'Referer':'https://example.test/?token='+d['marker']})
  assert r.status_code in (401,404,503)
print('synthetic_requests_completed')
"""
    p = subprocess.run(["docker", "exec", "-i", "aura-api", "python", "-B", "-c", code],
                       input=json.dumps({"marker": marker, "path": path}), text=True, capture_output=True, timeout=20)
    if p.returncode:
        raise RuntimeError()
    result = {}
    for name in ("aura-api", "aura-caddy"):
        for _ in range(12):
            logs = subprocess.run(["docker", "logs", "--since", since, "--tail", "1000", name], capture_output=True, timeout=5)
            if logs.returncode:
                raise RuntimeError()
            raw = logs.stdout + logs.stderr
            if marker.encode() in raw:
                raise RuntimeError()
            trace = [line for line in raw.splitlines() if path.encode() in line]
            if trace:
                # Access trace must be structured, query-free and retain useful status data.
                rows = [json.loads(line) for line in trace]
                if name == "aura-api":
                    assert any(r.get("path") == path and r.get("status") in (401,404) for r in rows)
                else:
                    assert any(r.get("request", {}).get("uri") == path and r.get("status") in (401,404,503) for r in rows)
                result[name] = {"synthetic_marker_absent": True, "access_trace_preserved": True}
                break
            time.sleep(0.25)
        else:
            raise RuntimeError()
    return result


if __name__ == "__main__":
    try:
        print(json.dumps(check()))
    except BaseException:
        print(json.dumps({"target_log_check_failed": True, "raw_logs_not_exported": True}))
        sys.exit(1)
