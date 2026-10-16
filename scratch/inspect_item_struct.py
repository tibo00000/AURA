import json
import urllib.request
from test_spotify_with_credentials import get_spotify_credentials, load_env_file
from test_spotify_user_oauth import refresh_user_token

env = load_env_file("server/.env")
client_id, client_secret = get_spotify_credentials()
refresh_token = env.get("SPOTIFY_REFRESH_TOKEN")
token = refresh_user_token(client_id, client_secret, refresh_token)

req = urllib.request.Request("https://api.spotify.com/v1/playlists/7oFJzJhQQRSQrBUGHKXlxt", headers={"Authorization": f"Bearer {token}"})
with urllib.request.urlopen(req) as resp:
    pl = json.loads(resp.read().decode('utf-8'))
    first_item = pl['items']['items'][0]
    print("first_item keys:", first_item.keys())
    print("first_item sample:", json.dumps(first_item, indent=2))
