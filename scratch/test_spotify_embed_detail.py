import json
import re
import sys
import urllib.request

if sys.platform == "win32":
    sys.stdout.reconfigure(encoding='utf-8')

embed_url = "https://open.spotify.com/embed/playlist/7oFJzJhQQRSQrBUGHKXlxt"
req = urllib.request.Request(
    embed_url,
    headers={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"}
)

with urllib.request.urlopen(req) as resp:
    html = resp.read().decode('utf-8')

match = re.search(r'<script id="__NEXT_DATA__" type="application/json">(.+?)</script>', html)
if match:
    data = json.loads(match.group(1))
    entity = data.get("props", {}).get("pageProps", {}).get("state", {}).get("data", {}).get("entity", {})
    trackList = entity.get("trackList", [])
    print("trackList length:", len(trackList))
    print("Entity keys:", entity.keys())
    print("Total tracks according to entity:", entity.get("track_count") or entity.get("total_tracks") or entity.get("count"))
    for k, v in entity.items():
        if not isinstance(v, (list, dict)):
            print(f"  {k}: {v}")
