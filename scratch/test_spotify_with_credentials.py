import base64
import json
import os
import re
import sys
import urllib.request
import urllib.parse
from typing import List, Dict, Any

if sys.platform == "win32":
    sys.stdout.reconfigure(encoding='utf-8')

def load_env_file(filepath: str) -> Dict[str, str]:
    """Lit un fichier .env sans bibliothèque tierce."""
    env = {}
    if not os.path.exists(filepath):
        return env
    with open(filepath, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            if "=" in line:
                key, val = line.split("=", 1)
                env[key.strip()] = val.strip().strip('"').strip("'")
    return env

def get_spotify_credentials() -> tuple[str, str]:
    # Recherche dans server/.env ou .env racine
    env = load_env_file("server/.env")
    if not env.get("SPOTIFY_CLIENT_ID"):
        env = load_env_file(".env")
        
    client_id = env.get("SPOTIFY_CLIENT_ID") or os.environ.get("SPOTIFY_CLIENT_ID")
    client_secret = env.get("SPOTIFY_CLIENT_SECRET") or os.environ.get("SPOTIFY_CLIENT_SECRET")
    
    if not client_id or not client_secret:
        raise ValueError(
            "Identifiants manquants ! Veuillez renseigner SPOTIFY_CLIENT_ID et SPOTIFY_CLIENT_SECRET dans server/.env"
        )
    return client_id, client_secret

def get_spotify_access_token(client_id: str, client_secret: str) -> str:
    """Effectue l'authentification officielle Client Credentials auprès de Spotify."""
    url = "https://accounts.spotify.com/api/token"
    auth_header = base64.b64encode(f"{client_id}:{client_secret}".encode('utf-8')).decode('utf-8')
    data = urllib.parse.urlencode({"grant_type": "client_credentials"}).encode('utf-8')
    
    req = urllib.request.Request(
        url,
        data=data,
        headers={
            "Authorization": f"Basic {auth_header}",
            "Content-Type": "application/x-www-form-urlencoded"
        }
    )
    with urllib.request.urlopen(req) as resp:
        res = json.loads(resp.read().decode('utf-8'))
        return res["access_token"]

def fetch_spotify_playlist_paginated(playlist_url: str):
    print("==========================================================")
    print("TEST D'EXTRACTION COMPLETE SPOTIFY AVEC IDENTIFIANTS API")
    print("==========================================================")
    
    client_id, client_secret = get_spotify_credentials()
    masked_id = client_id[:4] + "..." + client_id[-4:] if len(client_id) > 8 else "***"
    print(f"Identifiants chargés avec succès (Client ID : {masked_id})")
    
    print("Authentification auprès de Spotify (Client Credentials)...")
    token = get_spotify_access_token(client_id, client_secret)
    print("Token d'accès officiel obtenu avec succès !")
    
    match = re.search(r'playlist/([a-zA-Z0-9]+)', playlist_url)
    playlist_id = match.group(1) if match else playlist_url
    
    # 1. Infos générales de la playlist
    base_api = f"https://api.spotify.com/v1/playlists/{playlist_id}"
    req = urllib.request.Request(
        base_api,
        headers={"Authorization": f"Bearer {token}"}
    )
    with urllib.request.urlopen(req) as resp:
        pl_data = json.loads(resp.read().decode('utf-8'))

    playlist_name = pl_data.get("name", "Sans titre")
    owner = pl_data.get("owner", {}).get("display_name", "Inconnu")
    total_announced = pl_data.get("tracks", {}).get("total", 0)
    
    print(f"\nPlaylist cible : \"{playlist_name}\" par {owner}")
    print(f"Nombre total de titres déclaré par Spotify : {total_announced} titres\n")
    
    # 2. Pagination automatique
    all_tracks = []
    offset = 0
    limit = 100
    page_num = 1
    
    while True:
        tracks_api = f"https://api.spotify.com/v1/playlists/{playlist_id}/tracks?limit={limit}&offset={offset}"
        req = urllib.request.Request(
            tracks_api,
            headers={"Authorization": f"Bearer {token}"}
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
            isrc = track.get("external_ids", {}).get("isrc")
            
            all_tracks.append({
                "title": title,
                "artist": artists,
                "album": album,
                "duration_s": duration_s,
                "isrc": isrc
            })

        print(f"  [Page {page_num}] {len(all_tracks)}/{total_announced} titres récupérés...")
        offset += len(items)
        page_num += 1
        
        if offset >= total_announced or not page_data.get("next"):
            break

    print(f"\n----------------------------------------------------------")
    print(f"RÉSULTAT FINAL : {len(all_tracks)}/{total_announced} TITRES RÉCUPÉRÉS AVEC SUCCÈS !")
    print(f"----------------------------------------------------------")
    
    print("Échantillon du début de la playlist (Titres 1 à 3) :")
    for i, t in enumerate(all_tracks[:3], 1):
        print(f"  #{i}. {t['artist']} - {t['title']} ({t['duration_s']}s) [Album: {t['album']}]")
        
    print("\nÉchantillon de la fin de la playlist (Titres 238 à 240) :")
    for i, t in enumerate(all_tracks[-3:], len(all_tracks) - 2):
        print(f"  #{i}. {t['artist']} - {t['title']} ({t['duration_s']}s) [Album: {t['album']}]")

if __name__ == "__main__":
    playlist_url = "https://open.spotify.com/playlist/7oFJzJhQQRSQrBUGHKXlxt"
    fetch_spotify_playlist_paginated(playlist_url)
