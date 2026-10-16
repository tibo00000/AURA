import json
import sys
import urllib.request
from test_spotify_with_credentials import get_spotify_credentials, load_env_file
from test_spotify_user_oauth import refresh_user_token

if sys.platform == "win32":
    sys.stdout.reconfigure(encoding='utf-8')

env = load_env_file("server/.env")
client_id, client_secret = get_spotify_credentials()
refresh_token = env.get("SPOTIFY_REFRESH_TOKEN")

token = refresh_user_token(client_id, client_secret, refresh_token)

# 1. Fetch first page directly from playlist endpoint
req = urllib.request.Request(
    "https://api.spotify.com/v1/playlists/7oFJzJhQQRSQrBUGHKXlxt",
    headers={"Authorization": f"Bearer {token}"}
)
with urllib.request.urlopen(req) as resp:
    pl = json.loads(resp.read().decode('utf-8'))

playlist_name = pl.get("name")
owner = pl.get("owner", {}).get("display_name")
items_obj = pl.get("items", {})

total_expected = items_obj.get("total", 0)
print("=======================================================")
print(f"EXTRACTION COMPLETE SPOTIFY : \"{playlist_name}\" par {owner}")
print(f"Total de morceaux déclarés : {total_expected} morceaux")
print("=======================================================\n")

all_tracks = []
current_items = items_obj.get("items", [])

def extract_tracks(items_list):
    for entry in items_list:
        track = entry.get("item") or entry.get("track")
        if not track:
            continue
        artists = ", ".join([a["name"] for a in track.get("artists", [])])
        title = track.get("name")
        duration_s = track.get("duration_ms", 0) // 1000
        album = track.get("album", {}).get("name")
        images = track.get("album", {}).get("images", [])
        cover_url = images[0]["url"] if images else None
        isrc = track.get("external_ids", {}).get("isrc")
        
        all_tracks.append({
            "title": title,
            "artist": artists,
            "album": album,
            "duration_s": duration_s,
            "cover_url": cover_url,
            "isrc": isrc
        })

extract_tracks(current_items)
print(f"  [Page 1] {len(all_tracks)}/{total_expected} morceaux récupérés...")

next_url = items_obj.get("next")
page = 2
while next_url:
    req = urllib.request.Request(next_url, headers={"Authorization": f"Bearer {token}"})
    try:
        with urllib.request.urlopen(req) as resp:
            page_data = json.loads(resp.read().decode('utf-8'))
            extract_tracks(page_data.get("items", []))
            print(f"  [Page {page}] {len(all_tracks)}/{total_expected} morceaux récupérés...")
            next_url = page_data.get("next")
            page += 1
    except urllib.error.HTTPError as e:
        print(f"Erreur next_url {e.code} :", e.read().decode('utf-8'))
        break

print(f"\n=======================================================")
print(f"SUCCÈS TOTAL : {len(all_tracks)}/{total_expected} MORCEAUX EXTRAITS !")
print(f"=======================================================")

print("\n--- Début de la playlist (Morceaux 1 à 4) ---")
for i, t in enumerate(all_tracks[:4], 1):
    print(f"  #{i}. {t['artist']} - {t['title']} ({t['duration_s']}s) [Album: {t['album']}] (ISRC: {t['isrc']})")

print("\n--- Milieu de la playlist (Morceaux 129 à 132) ---")
for i, t in enumerate(all_tracks[128:132], 129):
    print(f"  #{i}. {t['artist']} - {t['title']} ({t['duration_s']}s) [Album: {t['album']}] (ISRC: {t['isrc']})")

print(f"\n--- Fin de la playlist (Morceaux {len(all_tracks)-3} à {len(all_tracks)}) ---")
for i, t in enumerate(all_tracks[-4:], len(all_tracks)-3):
    print(f"  #{i}. {t['artist']} - {t['title']} ({t['duration_s']}s) [Album: {t['album']}] (ISRC: {t['isrc']})")
