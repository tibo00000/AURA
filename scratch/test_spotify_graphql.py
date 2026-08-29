import json
import re
import sys
import urllib.request
import urllib.parse
from test_spotify_tokens import fetch_spotify_client_token

if sys.platform == "win32":
    sys.stdout.reconfigure(encoding='utf-8')

def fetch_spotify_graphql(playlist_id: str):
    # Récupération d'un web client token ou session
    client_token = fetch_spotify_client_token()
    print("Client Token:", client_token[:20] if client_token else "None")
    
    # Spotify Web API utilise le endpoint public GraphQL
    # On peut faire une requête à https://api-partner.spotify.com/pathfinder/v1/query
    # Operation: fetchPlaylistContents
    # Hash sha256 ou query
    # Testons d'abord si l'API publique spotipy/web scraper ou un fallback existe
    pass

