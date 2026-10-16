import json
import re
import sys
import urllib.request
import urllib.parse
from typing import List, Dict, Any, Optional

if sys.platform == "win32":
    sys.stdout.reconfigure(encoding='utf-8')

def fetch_deezer_playlist(playlist_url_or_id: str) -> Dict[str, Any]:
    print(f"\n--- [1] TEST EXTRACTION PLAYLIST DEEZER ---")
    print(f"URL/ID reçu : {playlist_url_or_id}")
    
    # 1. Résolution de l'ID depuis l'URL ou shortlink
    playlist_id = None
    if "deezer.page.link" in playlist_url_or_id or "link.deezer.com" in playlist_url_or_id:
        req = urllib.request.Request(
            playlist_url_or_id,
            headers={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"}
        )
        try:
            with urllib.request.urlopen(req) as resp:
                final_url = resp.geturl()
                print(f"Lien court résolu vers : {final_url}")
                match = re.search(r'playlist/(\d+)', final_url)
                if match:
                    playlist_id = match.group(1)
        except Exception as e:
            print(f"Erreur résolution lien court : {e}")

    if not playlist_id:
        match = re.search(r'playlist/(\d+)', playlist_url_or_id)
        if match:
            playlist_id = match.group(1)
        elif playlist_url_or_id.isdigit():
            playlist_id = playlist_url_or_id

    if not playlist_id:
        raise ValueError(f"Impossible d'extraire un ID de playlist Deezer depuis {playlist_url_or_id}")

    print(f"ID Deezer extrait : {playlist_id}")
    api_url = f"https://api.deezer.com/playlist/{playlist_id}"
    req = urllib.request.Request(api_url, headers={"User-Agent": "AuraMusic/1.0"})
    
    with urllib.request.urlopen(req) as resp:
        data = json.loads(resp.read().decode('utf-8'))
        
    if "error" in data:
        raise RuntimeError(f"Erreur API Deezer : {data['error']}")

    title = data.get("title", "Sans titre")
    creator = data.get("creator", {}).get("name", "Inconnu")
    tracks_data = data.get("tracks", {}).get("data", [])
    
    tracks = []
    for t in tracks_data:
        tracks.append({
            "title": t.get("title"),
            "artist": t.get("artist", {}).get("name"),
            "album": t.get("album", {}).get("title"),
            "duration_s": t.get("duration", 0),
            "cover_uri": t.get("album", {}).get("cover_big") or t.get("album", {}).get("cover_medium"),
            "isrc": t.get("isrc"),
            "deezer_id": str(t.get("id"))
        })

    result = {
        "source": "deezer",
        "playlist_id": playlist_id,
        "title": title,
        "creator": creator,
        "total_tracks": len(tracks),
        "tracks": tracks
    }
    
    print(f"Succès ! Playlist : \"{title}\" par {creator} ({len(tracks)} titres récupérés)")
    for i, tr in enumerate(tracks[:5], 1):
        print(f"  {i}. {tr['artist']} - {tr['title']} ({tr['duration_s']}s) [Album: {tr['album']}]")
    if len(tracks) > 5:
        print(f"  ... et {len(tracks) - 5} autres titres.")
        
    return result


def fetch_spotify_playlist(playlist_url_or_id: str) -> Dict[str, Any]:
    print(f"\n--- [2] TEST EXTRACTION PLAYLIST SPOTIFY (ZERO AUTH) ---")
    print(f"URL/ID reçu : {playlist_url_or_id}")
    
    match = re.search(r'playlist/([a-zA-Z0-9]+)', playlist_url_or_id)
    playlist_id = match.group(1) if match else playlist_url_or_id
    print(f"ID Spotify extrait : {playlist_id}")

    embed_url = f"https://open.spotify.com/embed/playlist/{playlist_id}"
    req = urllib.request.Request(
        embed_url,
        headers={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"}
    )
    
    with urllib.request.urlopen(req) as resp:
        html = resp.read().decode('utf-8')

    # Recherche du payload JSON embarqué __NEXT_DATA__
    json_match = re.search(r'<script id="__NEXT_DATA__" type="application/json">(.+?)</script>', html)
    if not json_match:
        # Fallback recherche de resource data
        json_match = re.search(r'data-testid="embed-props"[^>]*data-props="(.+?)"', html)
        
    if not json_match:
        raise RuntimeError("Impossible de localiser les métadonnées Spotify dans la page publique")

    next_data = json.loads(json_match.group(1))
    entity = next_data.get("props", {}).get("pageProps", {}).get("state", {}).get("data", {}).get("entity", {})
    if not entity:
        # Structure alternative embed
        entity = next_data.get("props", {}).get("pageProps", {}).get("entity", {})

    playlist_title = entity.get("title") or entity.get("name", "Playlist Spotify")
    track_list_raw = entity.get("trackList", [])
    
    tracks = []
    for t in track_list_raw:
        # Spotify embed structure : { title, subtitle, duration, uri }
        artist = t.get("subtitle") or t.get("artist") or "Artiste inconnu"
        title = t.get("title") or t.get("name")
        duration_ms = t.get("duration", 0)
        tracks.append({
            "title": title,
            "artist": artist,
            "duration_s": duration_ms // 1000 if duration_ms > 1000 else duration_ms,
            "spotify_uri": t.get("uri")
        })

    result = {
        "source": "spotify",
        "playlist_id": playlist_id,
        "title": playlist_title,
        "total_tracks": len(tracks),
        "tracks": tracks
    }
    
    print(f"Succès ! Playlist : \"{playlist_title}\" ({len(tracks)} titres récupérés sans authentification)")
    for i, tr in enumerate(tracks[:5], 1):
        print(f"  {i}. {tr['artist']} - {tr['title']} ({tr['duration_s']}s)")
    if len(tracks) > 5:
        print(f"  ... et {len(tracks) - 5} autres titres.")
        
    return result


def test_m3u8_export_and_import():
    print(f"\n--- [3] TEST EXPORT ET IMPORT FICHIER M3U8 STANDARD ---")
    
    # 1. Simulation de titres présents dans AURA
    aura_tracks = [
        {"id": "track:local:101", "title": "Get Lucky", "artist": "Daft Punk", "duration_ms": 248000, "uri": "content://media/external/audio/media/101"},
        {"id": "track:local:102", "title": "Blinding Lights", "artist": "The Weeknd", "duration_ms": 200000, "uri": "content://media/external/audio/media/102"},
        {"id": "track:local:103", "title": "Bolide Allemand", "artist": "SDM", "duration_ms": 178000, "uri": "content://media/external/audio/media/103"},
        {"id": "track:local:104", "title": "Positions", "artist": "Ariana Grande", "duration_ms": 172000, "uri": "content://media/external/audio/media/104"}
    ]
    
    # 2. Génération M3U8
    m3u8_lines = ["#EXTM3U", "#PLAYLIST:Mes Favoris Soirée"]
    for t in aura_tracks:
        duration_s = t["duration_ms"] // 1000
        m3u8_lines.append(f"#EXTINF:{duration_s},{t['artist']} - {t['title']}")
        m3u8_lines.append(t["uri"])
    m3u8_content = "\n".join(m3u8_lines)
    
    print("Contenu du fichier .m3u8 généré :")
    print("--------------------------------------------------")
    print(m3u8_content)
    print("--------------------------------------------------")
    
    # 3. Parsing et réconciliation de fichier M3U8 importé
    print("\nSimulation du Parsing d'un fichier .m3u8 avec du bruit / accents / formats mixtes...")
    imported_m3u = """#EXTM3U
#EXTINF:248,Daft Punk - Get Lucky
/storage/emulated/0/Music/Daft Punk - Get Lucky.mp3
#EXTINF:200,the weeknd - blinding lights
/storage/emulated/0/Download/blinding_lights.mp3
#EXTINF:178,S.D.M - Bolide allemand (Explicit)
/Music/SDM_bolide.flac
#EXTINF:180,Unknown Artist - Inconnu Au Bataillon
/Music/unknown.mp3
"""
    
    # Parsing
    parsed_items = []
    current_artist = None
    current_title = None
    current_duration = 0
    
    for line in imported_m3u.splitlines():
        line = line.strip()
        if not line or line.startswith("#EXTM3U") or line.startswith("#PLAYLIST"):
            continue
        if line.startswith("#EXTINF:"):
            # Format: #EXTINF:180,Artist - Title
            meta_part = line[8:]
            if "," in meta_part:
                dur_str, raw_name = meta_part.split(",", 1)
                try:
                    current_duration = int(dur_str)
                except ValueError:
                    current_duration = 0
                if " - " in raw_name:
                    current_artist, current_title = raw_name.split(" - ", 1)
                else:
                    current_artist = "Inconnu"
                    current_title = raw_name
        else:
            # Ligne de chemin de fichier / URI
            path = line
            if not current_title:
                # Extraire du nom de fichier
                filename = path.split("/")[-1].rsplit(".", 1)[0]
                if " - " in filename:
                    current_artist, current_title = filename.split(" - ", 1)
                else:
                    current_artist = "Inconnu"
                    current_title = filename
            
            parsed_items.append({
                "artist": (current_artist or "").strip(),
                "title": (current_title or "").strip(),
                "duration_s": current_duration,
                "path": path
            })
            current_artist, current_title, current_duration = None, None, 0

    print(f"{len(parsed_items)} morceaux parsés depuis le fichier M3U :")
    
    # Matching simple / normalisé contre la bibliothèque locale
    matched_count = 0
    for item in parsed_items:
        # Normalisation simple
        def norm(s):
            return re.sub(r'[^a-z0-9]', '', s.lower())

        item_key = norm(f"{item['artist']} {item['title']}")
        match_found = None
        for local_track in aura_tracks:
            local_key = norm(f"{local_track['artist']} {local_track['title']}")
            if norm(item['title']) in local_key or local_key in item_key or item_key in local_key:
                match_found = local_track
                break
                
        if match_found:
            matched_count += 1
            print(f"  [TROUVÉ LOCALEMENT] \"{item['artist']} - {item['title']}\" -> ID: {match_found['id']} (\"{match_found['artist']} - {match_found['title']}\")")
        else:
            print(f"  [NON TROUVÉ] \"{item['artist']} - {item['title']}\" -> Proposé au téléchargement ou recherche en ligne")

    print(f"\nRapport de matching : {matched_count}/{len(parsed_items)} morceaux trouvés dans la bibliothèque !")


if __name__ == "__main__":
    deezer_link = "https://www.deezer.com/fr/playlist/13775143881"
    deezer_short = "https://link.deezer.com/s/34ffsAU5TQQZEdH2TFWPh"
    spotify_link = "https://open.spotify.com/playlist/7oFJzJhQQRSQrBUGHKXlxt"
    
    # Test 1 : Deezer direct et lien court
    fetch_deezer_playlist(deezer_short)
    
    # Test 2 : Spotify public embed
    fetch_spotify_playlist(spotify_link)
    
    # Test 3 : M3U8 Export / Import & Matching
    test_m3u8_export_and_import()
