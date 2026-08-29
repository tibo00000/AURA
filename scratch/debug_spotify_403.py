import base64
import json
import os
import sys
import urllib.request
import urllib.parse
from test_spotify_with_credentials import get_spotify_credentials, get_spotify_access_token

if sys.platform == "win32":
    sys.stdout.reconfigure(encoding='utf-8')

client_id, client_secret = get_spotify_credentials()
token = get_spotify_access_token(client_id, client_secret)

playlist_id = "7oFJzJhQQRSQrBUGHKXlxt"
url = f"https://api.spotify.com/v1/playlists/{playlist_id}"

req = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}"})
try:
    with urllib.request.urlopen(req) as resp:
        print("Status code:", resp.getcode())
        data = json.loads(resp.read().decode('utf-8'))
        print(json.dumps(data, indent=2)[:1000])
except urllib.error.HTTPError as e:
    print(f"Error {e.code}:", e.read().decode('utf-8'))

url_tracks = f"https://api.spotify.com/v1/playlists/{playlist_id}/tracks"
req_tracks = urllib.request.Request(url_tracks, headers={"Authorization": f"Bearer {token}"})
try:
    with urllib.request.urlopen(req_tracks) as resp:
        print("Status code tracks:", resp.getcode())
        data = json.loads(resp.read().decode('utf-8'))
        print(json.dumps(data, indent=2)[:1000])
except urllib.error.HTTPError as e:
    print(f"Tracks Error {e.code}:", e.read().decode('utf-8'))
