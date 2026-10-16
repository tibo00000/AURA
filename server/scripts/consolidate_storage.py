#!/usr/bin/env python3
"""
Script d'audit, de consolidation et de nettoyage sécurisé du stockage VPS AURA.

Ce script inspecte les fichiers legacy `job_*.mp3` à la racine de DOWNLOADS_DIR,
vérifie leur intégrité cryptographique (SHA-256) et leurs inodes physiques,
et les consolide vers la structure canonique _global_cache avec mise en quarantaine
réversible (aucune suppression destructive immédiate).

Usage :
    # 1. Audit complet en lecture seule (recommandé avant toute action) :
    python server/scripts/consolidate_storage.py --preview

    # 2. Consolidation sécurisée avec mise en quarantaine réversible :
    python server/scripts/consolidate_storage.py --consolidate

    # 3. Purge de la quarantaine (uniquement pour les dossiers de plus de 7 jours) :
    python server/scripts/consolidate_storage.py --purge-trash
"""

import argparse
import hashlib
import json
import logging
import os
import shutil
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, List, Optional, Tuple

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)
logger = logging.getLogger("consolidate_storage")


def compute_sha256(file_path: Path, block_size: int = 65536) -> str:
    """Calcule le hash SHA-256 d'un fichier."""
    h = hashlib.sha256()
    with open(file_path, "rb") as f:
        for chunk in iter(lambda: f.read(block_size), b""):
            h.update(chunk)
    return h.hexdigest()


def load_env_file() -> dict:
    """Charge les variables d'environnement depuis les emplacements standards."""
    env_vars = {}
    candidates = [
        Path.cwd() / ".env",
        Path.cwd() / "server" / ".env",
        Path(__file__).resolve().parent.parent / ".env",
        Path("/opt/aura/server/.env"),
        Path("/opt/aura/.env"),
    ]
    for p in candidates:
        if p.exists() and p.is_file():
            try:
                with open(p, "r", encoding="utf-8") as f:
                    for line in f:
                        line = line.strip()
                        if not line or line.startswith("#") or "=" not in line:
                            continue
                        k, v = line.split("=", 1)
                        env_vars[k.strip()] = v.strip().strip("'\"")
                break
            except Exception:
                pass
    return env_vars


def get_supabase_client(custom_url: Optional[str] = None, custom_key: Optional[str] = None):
    """Initialise le client Supabase."""
    env = load_env_file()
    url = custom_url or os.environ.get("SUPABASE_URL") or env.get("SUPABASE_URL", "")
    key = custom_key or os.environ.get("SUPABASE_SERVICE_ROLE_KEY") or env.get("SUPABASE_SERVICE_ROLE_KEY", "")

    if not url or not key:
        logger.warning("SUPABASE_URL ou SUPABASE_SERVICE_ROLE_KEY manquant. L'audit DB sera limité.")
        return None

    try:
        from supabase import create_client
        return create_client(url, key)
    except ImportError:
        logger.warning("Module 'supabase' non installé dans cet environnement.")
        return None


def get_downloads_dir(custom_path: Optional[str] = None) -> Path:
    """Résout le chemin absolu du dossier de téléchargements."""
    env = load_env_file()
    path_str = custom_path or os.environ.get("DOWNLOADS_DIR") or env.get("DOWNLOADS_DIR", "/app/downloads")
    p = Path(path_str)
    return p if p.is_absolute() else Path.cwd() / p


def get_sync_base(downloads_dir: Path) -> Path:
    return downloads_dir / "sync_files"


def get_global_cache_dir(downloads_dir: Path) -> Path:
    return get_sync_base(downloads_dir) / "_global_cache"


def get_track_key(track_id: str) -> str:
    return hashlib.sha256(track_id.encode("utf-8")).hexdigest()


def get_user_key(user_id: str) -> str:
    return hashlib.sha256(user_id.encode("utf-8")).hexdigest()


def get_track_aliases(track_id: str) -> List[str]:
    """Résout les alias d'un track_id."""
    try:
        from app.core.aura_id_codec import get_track_id_aliases
        return get_track_id_aliases(track_id)
    except Exception:
        aliases = [track_id]
        if track_id.startswith("deezer:"):
            aliases.append(track_id.split(":", 1)[1])
        elif track_id.isdigit():
            aliases.append(f"deezer:{track_id}")
        return aliases


class StorageConsolidator:
    def __init__(self, downloads_dir: Path, supabase_client=None):
        self.downloads_dir = downloads_dir
        self.sync_base = get_sync_base(downloads_dir)
        self.global_cache = get_global_cache_dir(downloads_dir)
        self.trash_base = downloads_dir / "_legacy_trash"
        self.supabase = supabase_client
        self.now = time.time()

    def check_running_jobs(self) -> List[dict]:
        """Vérifie s'il existe des téléchargements actifs en cours."""
        if not self.supabase:
            return []
        try:
            res = self.supabase.table("download_jobs").select("id, track_id, title").eq("status", "running").execute()
            return res.data or []
        except Exception as e:
            logger.warning("Impossible de vérifier les jobs actifs dans Supabase: %s", e)
            return []

    def inspect_file(self, candidate_file: Path) -> dict:
        """
        Inspecte un fichier selon la matrice de sécurité à 7 cas (A à G).
        Retourne un dictionnaire contenant les métadonnées et la décision.
        """
        stat = candidate_file.stat()
        file_name = candidate_file.name
        file_size = stat.st_size
        file_mtime = stat.st_mtime
        file_ino = stat.st_ino
        age_seconds = self.now - file_mtime

        info = {
            "path": str(candidate_file),
            "file_name": file_name,
            "size_bytes": file_size,
            "mtime_iso": datetime.fromtimestamp(file_mtime, tz=timezone.utc).isoformat(),
            "age_minutes": round(age_seconds / 60, 1),
            "inode": file_ino,
            "nlink": stat.st_nlink,
            "sha256": None,
            "case": None,
            "status": None,
            "action": None,
            "job_id": None,
            "track_id": None,
            "user_id": None,
            "matched_cache_file": None,
            "reason": None,
        }

        # CAS G : Fichier corrompu avéré de 0 octet et âgé de plus de 2h
        if file_size == 0 and age_seconds > 7200:
            info["case"] = "G"
            info["status"] = "CORRUPTED_ZERO_BYTE"
            info["action"] = "REMOVE_CORRUPTED"
            info["reason"] = "Fichier vide (0 octet) inactif depuis plus de 2 heures."
            return info

        # CAS A : Fichier en cours d'écriture récent (< 30 min)
        if age_seconds < 1800:
            info["case"] = "A"
            info["status"] = "IN_PROGRESS_RECENT"
            info["action"] = "IGNORE"
            info["reason"] = "Fichier modifié il y a moins de 30 minutes, possible écriture en cours."
            return info

        # Extraction de job_id
        job_id = candidate_file.stem
        info["job_id"] = job_id

        # Recherche de l'entrée correspondante dans Supabase
        job_row = None
        if self.supabase:
            try:
                res = self.supabase.table("download_jobs").select("*").eq("id", job_id).limit(1).execute()
                if res.data:
                    job_row = res.data[0]
            except Exception as e:
                logger.warning("Erreur Supabase pour job %s: %s", job_id, e)

        # CAS B : Orphelin non résolu en base
        if not job_row or not job_row.get("track_id"):
            info["case"] = "B"
            info["status"] = "UNRESOLVED_ORPHAN"
            info["action"] = "PRESERVE_AND_FLAG"
            info["reason"] = "Aucune entrée Supabase trouvée pour ce job_id. Préservé sans modification."
            return info

        track_id = job_row["track_id"]
        user_id = job_row.get("user_id")
        job_status = job_row.get("status")

        info["track_id"] = track_id
        info["user_id"] = user_id

        # CAS A bis : Job encore marqué running en base
        if job_status == "running":
            info["case"] = "A"
            info["status"] = "JOB_RUNNING_IN_DB"
            info["action"] = "IGNORE"
            info["reason"] = "Job de téléchargement marqué 'running' en base de données."
            return info

        # Recherche dans _global_cache (avec réconciliation des alias)
        aliases = get_track_aliases(track_id)
        matched_cache_file = None
        for alias in aliases:
            alias_key = get_track_key(alias)
            cand_cache = self.global_cache / f"{alias_key}.audio"
            if cand_cache.exists() and cand_cache.stat().st_size > 0:
                matched_cache_file = cand_cache
                break

        # CAS C : Titre absent du Cache Global -> Migration requise
        if not matched_cache_file:
            info["case"] = "C"
            info["status"] = "ABSENT_FROM_GLOBAL_CACHE"
            info["action"] = "MIGRATE_TO_GLOBAL_CACHE"
            info["reason"] = "Titre absent de _global_cache. Doit être indexé avant tout déplacement."
            return info

        info["matched_cache_file"] = str(matched_cache_file)
        cache_stat = matched_cache_file.stat()

        # CAS D : Même Inode Physique (Déjà relié au même bloc disque)
        if file_ino == cache_stat.st_ino:
            info["case"] = "D"
            info["status"] = "HARDLINK_SAME_INODE"
            info["action"] = "MOVE_TO_QUARANTINE"
            info["reason"] = f"Même inode ({file_ino}) que {matched_cache_file.name}. Garantie physique identique."
            return info

        # CAS E ou F : Inodes distincts -> Vérification cryptographique SHA-256 obligatoire
        try:
            cand_sha = compute_sha256(candidate_file)
            cache_sha = compute_sha256(matched_cache_file)
            info["sha256"] = cand_sha

            if cand_sha == cache_sha:
                # CAS E : Inodes différents mais contenu rigoureusement identique bit à bit
                info["case"] = "E"
                info["status"] = "IDENTICAL_COPY_VERIFIED"
                info["action"] = "MOVE_TO_QUARANTINE"
                info["reason"] = f"Inodes différents mais SHA-256 identique ({cand_sha[:12]}...). Intégrité prouvée."
            else:
                # CAS F : Inodes différents ET hash différent (conflit de contenu)
                info["case"] = "F"
                info["status"] = "CONTENT_DIVERGENCE_CONFLICT"
                info["action"] = "PRESERVE_AND_FLAG"
                info["reason"] = f"Conflit de hash: {cand_sha[:12]} vs {cache_sha[:12]}. Préservé sans modification."
        except Exception as e:
            info["case"] = "F"
            info["status"] = "HASH_ERROR"
            info["action"] = "PRESERVE_AND_FLAG"
            info["reason"] = f"Erreur lors du calcul du hash: {e}. Préservé par sécurité."

        return info

    def scan_all(self) -> List[dict]:
        """Scanne tous les fichiers candidats à la racine de DOWNLOADS_DIR."""
        if not self.downloads_dir.exists():
            logger.error("Le dossier %s n'existe pas.", self.downloads_dir)
            return []

        candidates = []
        for p in self.downloads_dir.iterdir():
            # Ne traiter que les fichiers à la racine (pas les dossiers sync_files, _legacy_trash, etc.)
            if p.is_file() and p.name.startswith("job_"):
                candidates.append(p)

        results = []
        for c in sorted(candidates):
            info = self.inspect_file(c)
            results.append(info)

        return results

    def preview(self) -> dict:
        """Exécute l'audit complet en lecture seule."""
        running_jobs = self.check_running_jobs()
        if running_jobs:
            logger.warning("ATTENTION : %d job(s) de téléchargement sont actuellement 'running' en base !", len(running_jobs))

        inspections = self.scan_all()

        # Résumé statistique
        stats = {
            "total_files": len(inspections),
            "case_A_recent_in_progress": 0,
            "case_B_unresolved_orphans": 0,
            "case_C_migration_candidates": 0,
            "case_D_hardlinks_verified": 0,
            "case_E_identical_copies": 0,
            "case_F_conflicts_preserved": 0,
            "case_G_corrupted_zero_byte": 0,
            "bytes_quarantinable": 0,
        }

        print("\n" + "=" * 110)
        print(f" AUDIT DE STOCKAGE AURA (Mode --preview) | Dossier : {self.downloads_dir}")
        print("=" * 110)
        print(f"{'Fichier':<32} {'Taille':<9} {'Cas':<5} {'Statut':<24} {'Action Décidée'}")
        print("-" * 110)

        for item in inspections:
            c = item["case"] or "?"
            size_mb = f"{item['size_bytes'] / (1024*1024):.1f} Mo"
            print(f"{item['file_name']:<32} {size_mb:<9} [{c}]   {item['status']:<24} {item['action']}")

            if c == "A": stats["case_A_recent_in_progress"] += 1
            elif c == "B": stats["case_B_unresolved_orphans"] += 1
            elif c == "C": stats["case_C_migration_candidates"] += 1
            elif c == "D":
                stats["case_D_hardlinks_verified"] += 1
                stats["bytes_quarantinable"] += item["size_bytes"]
            elif c == "E":
                stats["case_E_identical_copies"] += 1
                stats["bytes_quarantinable"] += item["size_bytes"]
            elif c == "F": stats["case_F_conflicts_preserved"] += 1
            elif c == "G": stats["case_G_corrupted_zero_byte"] += 1

        print("-" * 110)
        print(f"Total fichiers scannés           : {stats['total_files']}")
        print(f"  [D] Hardlinks déjà en place     : {stats['case_D_hardlinks_verified']}")
        print(f"  [E] Copies identiques (SHA-256) : {stats['case_E_identical_copies']}")
        print(f"  [C] À migrer vers _global_cache : {stats['case_C_migration_candidates']}")
        print(f"  [B] Orphelins non résolus (protégés) : {stats['case_B_unresolved_orphans']}")
        print(f"  [F] Conflits de hash (protégés) : {stats['case_F_conflicts_preserved']}")
        print(f"  [A] Récents / en cours (ignorés): {stats['case_A_recent_in_progress']}")
        print(f"  [G] Fichiers vides corrompus    : {stats['case_G_corrupted_zero_byte']}")
        print("=" * 110)

        # Export du manifeste d'audit JSON
        timestamp = int(time.time())
        report_file = self.downloads_dir / f"storage_audit_{timestamp}.json"
        try:
            payload = {
                "timestamp": timestamp,
                "iso_date": datetime.now(timezone.utc).isoformat(),
                "downloads_dir": str(self.downloads_dir),
                "stats": stats,
                "files": inspections,
            }
            report_file.write_text(json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8")
            print(f"\n[OK] Rapport d'audit exporté : {report_file}")
        except Exception as e:
            logger.warning("Échec de l'export JSON : %s", e)

        return stats

    def consolidate(self) -> None:
        """
        Exécute la consolidation avec revérification dynamique temps réel
        et déplacement en quarantaine réversible.
        """
        running_jobs = self.check_running_jobs()
        if running_jobs:
            logger.error("Arrêt : Des téléchargements sont en cours (%d jobs running).", len(running_jobs))
            logger.error("Veuillez arrêter le conteneur ('docker stop aura-api') ou attendre la fin des téléchargements.")
            sys.exit(1)

        timestamp = int(time.time())
        quarantine_dir = self.trash_base / f"quarantine_{timestamp}"
        quarantine_dir.mkdir(parents=True, exist_ok=True)
        self.global_cache.mkdir(parents=True, exist_ok=True)

        logger.info("Dossier de quarantaine créé : %s", quarantine_dir)

        inspections = self.scan_all()
        manifest_records = []

        migrated_count = 0
        quarantined_count = 0
        preserved_count = 0
        removed_empty_count = 0

        for item in inspections:
            candidate_path = Path(item["path"])
            if not candidate_path.exists():
                continue

            # RÈGLE DE SÉCURITÉ : Revérification dynamique temps réel
            fresh_item = self.inspect_file(candidate_path)
            action = fresh_item["action"]
            case = fresh_item["case"]

            if action == "IGNORE" or case == "A":
                logger.info("Ignoré (récent ou en cours) : %s", candidate_path.name)
                fresh_item["executed_action"] = "IGNORED"

            elif action == "PRESERVE_AND_FLAG" or case in ("B", "F"):
                logger.info("Préservé par sécurité (%s) : %s", fresh_item["status"], candidate_path.name)
                preserved_count += 1
                fresh_item["executed_action"] = "PRESERVED"

            elif action == "MIGRATE_TO_GLOBAL_CACHE" or case == "C":
                # Cas C : Migration vers _global_cache puis liaison utilisateur
                track_id = fresh_item["track_id"]
                track_key = get_track_key(track_id)
                target_audio = self.global_cache / f"{track_key}.audio"
                target_json = self.global_cache / f"{track_key}.json"

                try:
                    # Préférer hardlink pour ne pas dupliquer l'espace disque
                    if not target_audio.exists():
                        try:
                            os.link(candidate_path, target_audio)
                        except Exception:
                            shutil.copyfile(candidate_path, target_audio)

                    if not target_json.exists():
                        meta = {
                            "track_id": track_id,
                            "synced": True,
                            "size_bytes": target_audio.stat().st_size,
                            "migrated_at": datetime.now(timezone.utc).isoformat(),
                        }
                        target_json.write_text(json.dumps(meta, ensure_ascii=False), encoding="utf-8")

                    # Lier également au sous-dossier utilisateur si user_id disponible
                    user_id = fresh_item.get("user_id")
                    if user_id:
                        user_dir = self.sync_base / get_user_key(user_id)
                        user_dir.mkdir(parents=True, exist_ok=True)
                        user_audio = user_dir / f"{track_key}.audio"
                        user_json = user_dir / f"{track_key}.json"
                        if not user_audio.exists():
                            try:
                                os.link(target_audio, user_audio)
                            except Exception:
                                shutil.copyfile(target_audio, user_audio)
                        if not user_json.exists():
                            shutil.copyfile(target_json, user_json)

                    # Maintenant que la présence dans _global_cache est garantie, déplacer en quarantaine
                    dest_trash = quarantine_dir / candidate_path.name
                    shutil.move(candidate_path, dest_trash)
                    migrated_count += 1
                    quarantined_count += 1
                    fresh_item["executed_action"] = "MIGRATED_AND_QUARANTINED"
                    logger.info("[OK] Migré vers cache global et mis en quarantaine : %s", candidate_path.name)
                except Exception as e:
                    logger.error("Échec de migration pour %s: %s", candidate_path.name, e)
                    fresh_item["executed_action"] = f"ERROR: {e}"

            elif action == "MOVE_TO_QUARANTINE" or case in ("D", "E"):
                # Cas D et E : Déplacement vers le dossier de quarantaine horodaté
                try:
                    dest_trash = quarantine_dir / candidate_path.name
                    shutil.move(candidate_path, dest_trash)
                    quarantined_count += 1
                    fresh_item["executed_action"] = "MOVED_TO_QUARANTINE"
                    logger.info("[OK] Mis en quarantaine : %s (Cas %s)", candidate_path.name, case)
                except Exception as e:
                    logger.error("Échec du déplacement de %s: %s", candidate_path.name, e)
                    fresh_item["executed_action"] = f"ERROR: {e}"

            elif action == "REMOVE_CORRUPTED" or case == "G":
                try:
                    candidate_path.unlink()
                    removed_empty_count += 1
                    fresh_item["executed_action"] = "REMOVED_EMPTY"
                    print(f"[OK] Fichier vide corrompu supprimé : {candidate_path.name}")
                except Exception as e:
                    logger.error("Échec de suppression pour %s: %s", candidate_path.name, e)
                    fresh_item["executed_action"] = f"ERROR: {e}"

            manifest_records.append(fresh_item)

        # Écriture du manifeste de sauvegarde / rollback
        manifest_file = quarantine_dir / "quarantine_manifest.json"
        manifest_data = {
            "timestamp": timestamp,
            "iso_date": datetime.now(timezone.utc).isoformat(),
            "quarantine_dir": str(quarantine_dir),
            "migrated_count": migrated_count,
            "quarantined_count": quarantined_count,
            "preserved_count": preserved_count,
            "removed_empty_count": removed_empty_count,
            "files": manifest_records,
        }
        manifest_file.write_text(json.dumps(manifest_data, indent=2, ensure_ascii=False), encoding="utf-8")

        print("\n" + "=" * 70)
        print(" BILAN DE LA CONSOLIDATION")
        print("=" * 70)
        print(f"Fichiers migrés vers cache global   : {migrated_count}")
        print(f"Fichiers placés en quarantaine     : {quarantined_count}")
        print(f"Fichiers protégés / orphelins      : {preserved_count}")
        print(f"Fichiers vides supprimés           : {removed_empty_count}")
        print(f"Dossier de quarantaine réversible : {quarantine_dir}")
        print(f"Manifeste de sécurité sauvegardé  : {manifest_file}")
        print("=" * 70)
        print("\nNOTE DE SÉCURITÉ : Aucun fichier audio n'a été définitivement détruit.")
        print(f"Pour restaurer instantanément si besoin :\n  mv {quarantine_dir}/* {self.downloads_dir}/")

    def purge_trash(self, force_immediate: bool = False, min_age_days: int = 7) -> None:
        """
        Supprime définitivement les dossiers de quarantaine ayant dépassé la période de rétention.
        """
        if not self.trash_base.exists():
            print("[*] Aucun dossier de quarantaine trouvé.")
            return

        quarantine_folders = [p for p in self.trash_base.iterdir() if p.is_dir() and p.name.startswith("quarantine_")]
        if not quarantine_folders:
            print("[*] Aucun dossier de quarantaine à purger.")
            return

        now = time.time()
        min_age_seconds = min_age_days * 86400

        purged_count = 0
        retained_count = 0

        for folder in quarantine_folders:
            folder_stat = folder.stat()
            age_seconds = now - folder_stat.st_mtime
            age_days = age_seconds / 86400

            if age_seconds < min_age_seconds and not force_immediate:
                print(f"[!] Dossier {folder.name} trop récent ({age_days:.1f} jours < {min_age_days} jours). Rétention active, non purgé.")
                retained_count += 1
            else:
                shutil.rmtree(folder)
                print(f"[OK] Dossier de quarantaine purgé définitivement : {folder.name} (Âge: {age_days:.1f} jours)")
                purged_count += 1

        print(f"\nBilan purge : {purged_count} purgé(s), {retained_count} sous rétention.")


def main():
    parser = argparse.ArgumentParser(
        description="Audit et consolidation sécurisée du stockage VPS AURA."
    )
    parser.add_argument(
        "--preview",
        action="store_true",
        default=False,
        help="Mode audit en lecture seule (analyse chaque fichier, ne modifie rien).",
    )
    parser.add_argument(
        "--consolidate",
        action="store_true",
        default=False,
        help="Exécute la consolidation avec mise en quarantaine réversible.",
    )
    parser.add_argument(
        "--purge-trash",
        action="store_true",
        default=False,
        help="Purge les dossiers de quarantaine âgés de plus de 7 jours.",
    )
    parser.add_argument(
        "--force-immediate-purge",
        action="store_true",
        default=False,
        help="Désactive la garde de rétention de 7 jours lors de la purge.",
    )
    parser.add_argument(
        "--downloads-dir",
        type=str,
        default=None,
        help="Surcharger le chemin absolu de DOWNLOADS_DIR.",
    )
    parser.add_argument(
        "--supabase-url",
        type=str,
        default=None,
        help="Surcharger l'URL Supabase.",
    )
    parser.add_argument(
        "--service-key",
        type=str,
        default=None,
        help="Surcharger la clé de service Supabase.",
    )

    args = parser.parse_args()

    downloads_path = get_downloads_dir(args.downloads_dir)
    supabase = get_supabase_client(args.supabase_url, args.service_key)
    consolidator = StorageConsolidator(downloads_path, supabase)

    if args.purge_trash:
        consolidator.purge_trash(force_immediate=args.force_immediate_purge)
    elif args.consolidate:
        consolidator.consolidate()
    else:
        # Par défaut : mode preview
        consolidator.preview()


if __name__ == "__main__":
    main()
