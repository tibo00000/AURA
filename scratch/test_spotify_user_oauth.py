import base64
import json
import os
import re
import sys
import urllib.request
import urllib.parse
from test_spotify_with_credentials import get_spotify_credentials, load_env_file

if sys.platform == "win32":
    sys.stdout.reconfigure(encoding='utf-8')

REDIRECT_URI = "https://example.com/callback"
SCOPES = "playlist-read-private playlist-read-collaborative user-library-read"

def generate_new_auth_url():
    client_id, _ = get_spotify_credentials()
    params = {
        "client_id": client_id,
        "response_type": "code",
        "redirect_uri": REDIRECT_URI,
        "scope": SCOPES,
        "show_dialog": "true"
    }
    url = f"https://accounts.spotify.com/authorize?{urllib.parse.urlencode(params)}"
    print("\nLien d'autorisation Spotify :")
    print(url)
    return url

def update_env_file(key: str, value: str):
    env_path = "server/.env"
    lines = []
    found = False
    if os.path.exists(env_path):
        with open(env_path, "r", encoding="utf-8") as f:
            lines = f.readlines()
            
    new_lines = []
    for line in lines:
        if line.strip().startswith(f"{key}="):
            new_lines.append(f"{key}={value}\n")
            found = True
        else:
            new_lines.append(line)
            
    if not found:
        if new_lines and not new_lines[-1].endswith("\n"):
            new_lines.append("\n")
        new_lines.append(f"{key}={value}\n")
        
    with open(env_path, "w", encoding="utf-8") as f:
        f.writelines(new_lines)

def refresh_user_token(client_id: str, client_secret: str, refresh_token: str) -> str:
    url = "https://accounts.spotify.com/api/token"
    auth_header = base64.b64encode(f"{client_id}:{client_secret}".encode('utf-8')).decode('utf-8')
    data = urllib.parse.urlencode({
        "grant_type": "refresh_token",
        "refresh_token": refresh_token
    }).encode('utf-8')
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

def run():
    env = load_env_file("server/.env")
    client_id, client_secret = get_spotify_credentials()
    
    refresh_token = env.get("SPOTIFY_REFRESH_TOKEN")
    auth_code_raw = env.get("SPOTIFY_AUTH_CODE")
    
    user_access_token = None
    
    if refresh_token:
        print("Utilisation du Refresh Token permanent existant dans server/.env...")
        try:
            user_access_token = refresh_user_token(client_id, client_secret, refresh_token)
            print("Nouveau User Access Token généré via Refresh Token !")
        except Exception as e:
            print("Erreur refresh token :", e)

    if not user_access_token and auth_code_raw:
        code = auth_code_raw.strip()
        if "code=" in code:
            m = re.search(r'code=([^&]+)', code)
            if m:
                code = m.group(1)
        print("Échange du nouveau code...")
        url = "https://accounts.spotify.com/api/token"
        auth_header = base64.b64encode(f"{client_id}:{client_secret}".encode('utf-8')).decode('utf-8')
        data = urllib.parse.urlencode({
            "grant_type": "authorization_code",
            "code": code,
            "redirect_uri": REDIRECT_URI
        }).encode('utf-8')
        req = urllib.request.Request(
            url,
            data=data,
            headers={
                "Authorization": f"Basic {auth_header}",
                "Content-Type": "application/x-www-form-urlencoded"
            }
        )
        try:
            with urllib.request.urlopen(req) as resp:
                res = json.loads(resp.read().decode('utf-8'))
                user_access_token = res.get("access_token")
                new_refresh = res.get("refresh_token")
                if new_refresh:
                    print("Sauvegarde automatique de SPOTIFY_REFRESH_TOKEN dans server/.env !")
                    update_env_file("SPOTIFY_REFRESH_TOKEN", new_refresh)
        except urllib.error.HTTPError as e:
            print(f"Erreur échange code ({e.code}) :", e.read().decode('utf-8'))
            print("Veuillez régénérer un code avec le lien ci-dessous.")
            generate_new_auth_url()
            return

    if not user_access_token:
        print("Aucun token valide. Génération du lien d'autorisation...")
        generate_new_auth_url()
        return

    # Test avec user token
    playlist_id = "7oFJzJhQQRSQrBUGHKXlxt"
    all_tracks = []
    offset = 0
    limit = 100
    page_num = 1
    
    print(f"\nRécupération complète des morceaux de 'Titi 🐢' ({playlist_id})...")
    while True:
        tracks_api = f"https://api.spotify.com/v1/playlists/{playlist_id}/tracks?limit={limit}&offset={offset}"
        req = urllib.request.Request(
            tracks_api,
            headers={"Authorization": f"Bearer {user_access_token}"}
        )
        try:
            with urllib.request.urlopen(req) as resp:
                page_data = json.loads(resp.read().decode('utf-8'))
        except urllib.error.HTTPError as e:
            print(f"Erreur API ({e.code}) :", e.read().decode('utf-8'))
            break

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
            all_tracks.append({
                "title": title,
                "artist": artists,
                "album": album,
                "duration_s": duration_s
            })

        print(f"  [Page {page_num}] {len(all_tracks)} titres extraits...")
        offset += len(items)
        page_num += 1
        if not page_data.get("next"):
            break

    print(f"\n=======================================================")
    print(f"SUCCES TOTAL : {len(all_tracks)} TITRES RECUPERES SUR SPOTIFY !")
    print(f"=======================================================")
    if all_tracks:
        print("Premiers titres :")
        for i, t in enumerate(all_tracks[:3], 1):
            print(f"  #{i}. {t['artist']} - {t['title']} ({t['duration_s']}s)")
        print("...\nDerniers titres :")
        for i, t in enumerate(all_tracks[-3:], len(all_tracks) - 2):
            print(f"  #{i}. {t['artist']} - {t['title']} ({t['duration_s']}s)")

if __name__ == "__main__":
    run()
