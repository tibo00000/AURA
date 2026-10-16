"""
Script de diagnostic et nettoyage des favoris orphelins dans Supabase et la base locale Desktop.

Permet de visualiser les deux générations de favoris (les 101 favoris antérieurs à la perte de données
et les 90 favoris recréés après) et de purger proprement l'ancienne génération.

Usage:
    # Prévisualiser les deux lots de favoris sans rien modifier :
    python server/scripts/clean_favorites.py --preview

    # Supprimer l'ancien lot (101 favoris d'avant le 13 août) pour ne garder que la sélection actuelle :
    python server/scripts/clean_favorites.py --delete-older

    # Supprimer le lot récent (90 favoris après le 13 août) :
    python server/scripts/clean_favorites.py --delete-newer
"""

import argparse
import os
import sqlite3
import sys
from pathlib import Path

# Ajuster le chemin pour importer app.config
server_dir = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(server_dir))

from app.config import get_settings
from supabase import create_client

CUTOFF_ISO = "2026-08-13T00:00:00.000000+00:00"


def get_supabase_client():
    settings = get_settings()
    key = settings.supabase_service_role_key or settings.supabase_anon_key
    if not settings.supabase_url or not key:
        print("Erreur: SUPABASE_URL ou clé manquante dans .env")
        sys.exit(1)
    return create_client(settings.supabase_url, key)


def get_local_db_connection():
    db_path = os.path.expanduser("~/.aura/aura.db")
    if os.path.exists(db_path):
        return sqlite3.connect(db_path)
    return None


def fetch_likes(supabase):
    res = supabase.table("likes").select("*").order("liked_at").execute()
    return res.data or []


def preview_likes(likes):
    local_conn = get_local_db_connection()
    cur = local_conn.cursor() if local_conn else None

    def get_track_title(track_id):
        if cur:
            r = cur.execute("SELECT title, display_artist_name FROM tracks WHERE id = ?", (track_id,)).fetchone()
            if r and r[0]:
                return f"{r[0]} — {r[1] or 'Inconnu'}"
        return track_id

    older = [l for l in likes if l["liked_at"] < CUTOFF_ISO]
    newer = [l for l in likes if l["liked_at"] >= CUTOFF_ISO]

    print("=" * 70)
    print(f"ANALYSE DES FAVORIS CLOUD (Total: {len(likes)} favoris)")
    print("=" * 70)
    print(f"\n[Lot 1 - Ancien lot (avant le 13 août)] : {len(older)} titres")
    print(f"Dates : de {older[0]['liked_at'][:10] if older else 'N/A'} à {older[-1]['liked_at'][:10] if older else 'N/A'}")
    print("Exemples de titres :")
    for lk in older[:10]:
        print(f"  - {get_track_title(lk['track_id'])} (ID: {lk['track_id']})")
    if len(older) > 10:
        print(f"  ... et {len(older) - 10} autres titres.")

    print(f"\n[Lot 2 - Lot récent (après le 13 août)] : {len(newer)} titres")
    print(f"Dates : de {newer[0]['liked_at'][:10] if newer else 'N/A'} à {newer[-1]['liked_at'][:10] if newer else 'N/A'}")
    print("Exemples de titres :")
    for lk in newer[:10]:
        print(f"  - {get_track_title(lk['track_id'])} (ID: {lk['track_id']})")
    if len(newer) > 10:
        print(f"  ... et {len(newer) - 10} autres titres.")
    print("=" * 70)


def purge_batch(supabase, target_likes, label):
    if not target_likes:
        print(f"Aucun favori à supprimer pour le lot : {label}")
        return

    print(f"Suppression de {len(target_likes)} favoris ({label})...")
    track_ids = [l["track_id"] for l in target_likes]

    # 1. Suppression dans Supabase par lots de 50
    chunk_size = 50
    for i in range(0, len(track_ids), chunk_size):
        chunk = track_ids[i:i + chunk_size]
        supabase.table("likes").delete().in_("track_id", chunk).execute()
        print(f"  - Supabase : {min(i + chunk_size, len(track_ids))}/{len(track_ids)} supprimés")

    # 2. Nettoyage dans la base SQLite locale Desktop (~/.aura/aura.db)
    local_conn = get_local_db_connection()
    if local_conn:
        cur = local_conn.cursor()
        cur.execute("BEGIN TRANSACTION")
        for tid in track_ids:
            cur.execute("DELETE FROM track_likes WHERE track_id = ?", (tid,))
            cur.execute("UPDATE tracks SET is_liked = 0 WHERE id = ?", (tid,))
        local_conn.commit()
        local_conn.close()
        print(f"  - Base locale Desktop (~/.aura/aura.db) nettoyée avec succès.")

    print(f"Nettoyage terminé ! Il reste désormais {191 - len(target_likes)} favoris dans le cloud.")


def main():
    parser = argparse.ArgumentParser(description="Nettoyage des favoris Supabase & Desktop")
    parser.add_argument("--preview", action="store_true", help="Afficher l'état sans modifier")
    parser.add_argument("--delete-older", action="store_true", help="Supprimer les anciens favoris (avant le 13 août)")
    parser.add_argument("--delete-newer", action="store_true", help="Supprimer les récents favoris (après le 13 août)")

    args = parser.parse_args()
    supabase = get_supabase_client()
    likes = fetch_likes(supabase)

    if args.delete_older:
        target = [l for l in likes if l["liked_at"] < CUTOFF_ISO]
        purge_batch(supabase, target, "Anciens favoris avant le 13 août")
    elif args.delete_newer:
        target = [l for l in likes if l["liked_at"] >= CUTOFF_ISO]
        purge_batch(supabase, target, "Nouveaux favoris après le 13 août")
    else:
        preview_likes(likes)


if __name__ == "__main__":
    main()
