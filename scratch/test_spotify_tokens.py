import json
import re
import sys
import urllib.request
import urllib.parse
from typing import List, Dict, Any

if sys.platform == "win32":
    sys.stdout.reconfigure(encoding='utf-8')

def fetch_spotify_client_token() -> str:
    """Récupère le client token public Spotify utilisé par le client web officiel."""
    url = "https://clienttoken.spotify.com/v1/clienttoken"
    payload = json.dumps({
        "client_data": {
            "client_version": "1.2.32.99.g4f909ef4",
            "client_id": "d8a5ed958d274c2e8ee717e6a4b0971d",
            "js_sdk_data": {
                "device_brand": "unknown",
                "device_model": "desktop",
                "os": "Windows",
                "os_version": "NT 10.0"
            }
        }
    }).encode('utf-8')
    
    req = urllib.request.Request(
        url,
        data=payload,
        headers={
            "Content-Type": "application/json",
            "Accept": "application/json",
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        }
    )
    with urllib.request.urlopen(req) as resp:
        data = json.loads(resp.read().decode('utf-8'))
        return data.get("granted_token", {}).get("token")

# Test Spotify token extraction via HTML session or GraphQL
def fetch_spotify_web_access_token() -> str:
    # 1. Requête sur open.spotify.com pour obtenir le cookie sp_t / session
    req = urllib.request.Request(
        "https://open.spotify.com",
        headers={
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        }
    )
    with urllib.request.urlopen(req) as resp:
        html = resp.read().decode('utf-8')
        # Chercher accessToken dans le HTML ou les scripts
        token_match = re.search(r'"accessToken":"([^"]+)"', html)
        if token_match:
            return token_match.group(1)
        
        # Chercher session data
        session_match = re.search(r'<script id="session" type="application/json">(.+?)</script>', html)
        if session_match:
            session_data = json.loads(session_match.group(1))
            return session_data.get("accessToken")
            
    return None

print("Test client token:", fetch_spotify_client_token()[:20] if fetch_spotify_client_token() else "None")
print("Test web access token:", fetch_spotify_web_access_token()[:20] if fetch_spotify_web_access_token() else "None")
