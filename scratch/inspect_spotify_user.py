import json
import urllib.request
from test_spotify_with_credentials import get_spotify_credentials, load_env_file
from test_spotify_user_oauth import refresh_user_token

env = load_env_file("server/.env")
client_id, client_secret = get_spotify_credentials()
refresh_token = env.get("SPOTIFY_REFRESH_TOKEN")

token = refresh_user_token(client_id, client_secret, refresh_token)

# 1. Inspect /v1/me
req = urllib.request.Request("https://api.spotify.com/v1/me", headers={"Authorization": f"Bearer {token}"})
with urllib.request.urlopen(req) as resp:
    me = json.loads(resp.read().decode('utf-8'))
    print("Logged in user ID:", me.get("id"))
    print("Logged in user Name:", me.get("display_name"))
    print("Logged in user Email:", me.get("email"))

# 2. Inspect playlist details
req = urllib.request.Request("https://api.spotify.com/v1/playlists/7oFJzJhQQRSQrBUGHKXlxt", headers={"Authorization": f"Bearer {token}"})
with urllib.request.urlopen(req) as resp:
    pl = json.loads(resp.read().decode('utf-8'))
    print("Playlist Name:", pl.get("name"))
    print("Playlist Owner ID:", pl.get("owner", {}).get("id"))
    print("Playlist Owner Name:", pl.get("owner", {}).get("display_name"))
    print("Playlist Public:", pl.get("public"))
    print("Playlist Collaborative:", pl.get("collaborative"))

# 3. Why did tracks fail?
# Since November 2024, Spotify removed the /v1/playlists/{id}/tracks endpoint for all non-extended-quota apps or deprecated the tracks sub-resource in favor of GraphQL.
