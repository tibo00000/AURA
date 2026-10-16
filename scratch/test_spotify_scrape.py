import json
import re
import sys
import urllib.request
import urllib.parse
from typing import List, Dict, Any

if sys.platform == "win32":
    sys.stdout.reconfigure(encoding='utf-8')

def fetch_spotify_anonymous_token_via_auth() -> str:
    """Utilise l'endpoint de client credentials anonyme public Spotify."""
    # Endpoint public de Spotify pour les embeds et le web
    url = "https://accounts.spotify.com/api/token"
    # Client ID public web Spotify officiel
    # Ce client_id + client_secret ou anonyme est public
    # On peut aussi utiliser l'anonymization proxy ou scraper le partner API
    pass

# Test extraction via Spotify Web Page directly
def fetch_spotify_playlist_page(playlist_id: str):
    url = f"https://open.spotify.com/playlist/{playlist_id}"
    req = urllib.request.Request(
        url,
        headers={
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept-Language": "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7"
        }
    )
    with urllib.request.urlopen(req) as resp:
        html = resp.read().decode('utf-8')
        
    print(f"Longueur HTML Spotify : {len(html)} octets")
    
    # Chercher les scripts JSON
    scripts = re.findall(r'<script[^>]*type="application/json"[^>]*>(.+?)</script>', html)
    print(f"Trouvé {len(scripts)} blocs JSON dans la page.")
    for i, s in enumerate(scripts):
        try:
            d = json.loads(s)
            print(f"  Bloc {i}: keys = {list(d.keys()) if isinstance(d, dict) else type(d)}")
        except:
            pass

    # Chercher les pistes dans les balises meta ou structures
    # Spotify met les tracks dans <meta name="music:song" ...> ou dans schema.org / JSON-LD
    json_ld_matches = re.findall(r'<script type="application/ld\+json">(.+?)</script>', html)
    print(f"Trouvé {len(json_ld_matches)} blocs JSON-LD")
    for j in json_ld_matches:
        try:
            d = json.loads(j)
            print(f"  JSON-LD type: {d.get('@type')}")
            if "track" in d:
                print(f"  Tracks dans JSON-LD : {len(d['track'])}")
        except Exception as e:
            print("  JSON-LD err:", e)

fetch_spotify_playlist_page("7oFJzJhQQRSQrBUGHKXlxt")
