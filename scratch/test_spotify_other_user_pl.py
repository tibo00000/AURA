import json
import urllib.request
from test_spotify_with_credentials import get_spotify_credentials, load_env_file
from test_spotify_user_oauth import refresh_user_token

env = load_env_file("server/.env")
client_id, client_secret = get_spotify_credentials()
refresh_token = env.get("SPOTIFY_REFRESH_TOKEN")

token = refresh_user_token(client_id, client_secret, refresh_token)

# Top 50 Global : 37i9dQZEVXbMDoHDwVN2tF
# Top 50 France : 37i9dQZEVXbIPWwFssbupI
for pl_id in ["37i9dQZEVXbMDoHDwVN2tF", "37i9dQZEVXbIPWwFssbupI"]:
    req = urllib.request.Request(
        f"https://api.spotify.com/v1/playlists/{pl_id}",
        headers={"Authorization": f"Bearer {token}"}
    )
    try:
        with urllib.request.urlopen(req) as resp:
            pl = json.loads(resp.read().decode('utf-8'))
            print(f"Playlist : \"{pl.get('name')}\" par {pl.get('owner', {}).get('display_name')}")
            items_obj = pl.get("items", {})
            print(f"  Total déclarés : {items_obj.get('total')}")
            print(f"  Morceaux reçus page 1 : {len(items_obj.get('items', []))}")
            first_it = items_obj.get('items', [])[0]
            tr = first_it.get('item') or first_it.get('track')
            if tr:
                art = ", ".join([a['name'] for a in tr.get('artists', [])])
                print(f"  #1. {art} - {tr.get('name')}")
    except Exception as e:
        print(f"Error {pl_id}: {e}")
