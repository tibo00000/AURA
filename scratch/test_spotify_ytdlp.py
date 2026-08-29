import json
import sys
import yt_dlp

if sys.platform == "win32":
    sys.stdout.reconfigure(encoding='utf-8')

ydl_opts = {
    'extract_flat': True,
    'skip_download': True,
    'quiet': True,
}

with yt_dlp.YoutubeDL(ydl_opts) as ydl:
    info = ydl.extract_info("https://open.spotify.com/playlist/7oFJzJhQQRSQrBUGHKXlxt", download=False)
    entries = info.get('entries', [])
    print(f"Playlist Title: {info.get('title')}")
    print(f"Total entries extracted by yt_dlp: {len(entries)}")
    for i, e in enumerate(list(entries)[:5], 1):
        print(f"  {i}. {e.get('artist')} - {e.get('title')} ({e.get('duration')}s)")
    if len(entries) > 5:
        print(f"  ... et fin:")
        for i, e in enumerate(list(entries)[-5:], len(entries)-4):
            print(f"  {i}. {e.get('artist')} - {e.get('title')} ({e.get('duration')}s)")
