import json
import re
import sys
import urllib.request
import urllib.parse
from typing import List, Dict, Any

if sys.platform == "win32":
    sys.stdout.reconfigure(encoding='utf-8')

def get_spotify_anonymous_token() -> str:
    """Récupère un token d'accès anonyme Spotify Web (aucun compte ni clé API requis)."""
    token_url = "https://open.spotify.com/get_access_token?reason=transport&productType=web_player"
    req = urllib.request.Request(
        token_url,
        headers={
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        }
    )
    with urllib.request.urlopen(req) as resp:
        data = json.loads(resp.read().decode('utf-8'))
        token = data.get("accessToken")
        if not token:
            raise RuntimeError("Impossible de récupérer le token anonyme Spotify")
        return token

def fetch_full_spotify_playlist(playlist_url_or_id: str) -> Dict[str, Any]:
    print(f"\n--- TEST EXTRACTION COMPLETE SPOTIFY (AVEC PAGINATION) ---")
    match = re.search(r'playlist/([a-zA-Z0-9]+)', playlist_url_or_id)
    playlist_id = match.group(1) if match else playlist_url_or_id
    
    print(f"1. Récupération du token anonyme Spotify Web...")
    token = get_spotify_anonymous_token()
    print(f"Token anonyme obtenu avec succès !")

    # 2. Récupération des infos de la playlist
    base_api = f"https://api.spotify.com/v1/playlists/{playlist_id}"
    req = urllib.request.Request(
        base_api,
        headers={
            "Authorization": f"Bearer {token}",
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
        }
    )
    with urllib.request.urlopen(req) as resp:
        pl_data = json.loads(resp.read().decode('utf-8'))

    playlist_name = pl_data.get("name", "Playlist Spotify")
    total_expected = pl_data.get("tracks", {}).get("total", 0)
    print(f"Playlist : \"{playlist_name}\" | Total attendu annoncé par Spotify : {total_expected} titres")

    # 3. Boucle de pagination pour TOUT récupérer (par tranches de 100)
    all_tracks = []
    offset = 0
    limit = 100
    
    while True:
        tracks_api = f"https://api.spotify.com/v1/playlists/{playlist_id}/tracks?limit={limit}&offset={offset}"
        req = urllib.request.Request(
            tracks_api,
            headers={
                "Authorization": f"Bearer {token}",
                "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
            }
        )
        with urllib.request.urlopen(req) as resp:
            page_data = json.loads(resp.read().decode('utf-8'))

        items = page_data.get("items", [])
        if not items:
            break

        for item in items:
            track = item.get("track")
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
                "cover_uri": cover_url,
                "isrc": isrc
            })

        print(f"  -> Récupéré {len(all_tracks)}/{total_expected} titres (offset={offset})...")
        offset += len(items)
        if offset >= total_expected or not page_data.get("next"):
            break

    print(f"\nSuccès total ! {len(all_tracks)} titres extraits sur {total_expected} attendus.")
    print(f"Derniers titres de la playlist (fin des 240) :")
    for i, tr in enumerate(all_tracks[-5:], len(all_tracks) - 4):
        print(f"  {i}. {tr['artist']} - {tr['title']} ({tr['duration_s']}s) [Album: {tr['album']}]")

    return {
        "title": playlist_name,
        "total": len(all_tracks),
        "tracks": all_tracks
    }

if __name__ == "__main__":
    fetch_full_spotify_playlist("https://open.spotify.com/playlist/7oFJzJhQQRSQrBUGHKXlxt")
