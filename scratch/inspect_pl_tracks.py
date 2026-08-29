import json
import urllib.request
from test_spotify_with_credentials import get_spotify_credentials, load_env_file
from test_spotify_user_oauth import refresh_user_token

env = load_env_file("server/.env")
client_id, client_secret = get_spotify_credentials()
refresh_token = env.get("SPOTIFY_REFRESH_TOKEN")

token = refresh_user_token(client_id, client_secret, refresh_token)

# Fetch playlist directly
req = urllib.request.Request(
    "https://api.spotify.com/v1/playlists/7oFJzJhQQRSQrBUGHKXlxt",
    headers={"Authorization": f"Bearer {token}"}
)
with urllib.request.urlopen(req) as resp:
    pl = json.loads(resp.read().decode('utf-8'))
    items = pl.get('items')
    print("pl['items'] type:", type(items))
    if isinstance(items, list):
        print(f"pl['items'] length: {len(items)}")
        if items:
            print("First item sample:", json.dumps(items[0], indent=2)[:300])
    elif isinstance(items, dict):
        print("pl['items'] keys:", items.keys())
