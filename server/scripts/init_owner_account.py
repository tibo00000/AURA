"""
Script d'initialisation du compte propriétaire Supabase Auth (100% Autonome).

Crée ou met à jour le compte utilisateur Supabase Auth avec l'identifiant exact:
12345678-1234-1234-1234-1234567890ab
garantissant la préservation intégrale de toutes les playlists, favoris,
snapshots de lecture et fichiers Cloud existants.

Usage:
    python server/scripts/init_owner_account.py --email mon-email@example.com --password mon_mot_de_passe
"""

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

OWNER_UUID = "12345678-1234-1234-1234-1234567890ab"


def load_env_file() -> dict:
    """Recherche et charge les variables d'environnement depuis un fichier .env."""
    env_vars = {}
    candidate_paths = [
        Path.cwd() / ".env",
        Path.cwd() / "server" / ".env",
        Path(__file__).resolve().parent.parent / ".env",
        Path(__file__).resolve().parent.parent.parent / ".env",
        Path("/opt/aura/server/.env"),
        Path("/opt/aura/.env"),
    ]

    for p in candidate_paths:
        if p.exists() and p.is_file():
            try:
                with open(p, "r", encoding="utf-8") as f:
                    for line in f:
                        line = line.strip()
                        if not line or line.startswith("#") or "=" not in line:
                            continue
                        k, v = line.split("=", 1)
                        k = k.strip()
                        v = v.strip().strip("'\"")
                        env_vars[k] = v
                break
            except Exception:
                pass

    return env_vars


def api_request(url: str, method: str, headers: dict, data: dict = None) -> tuple[int, dict]:
    """Exécute une requête HTTP avec la bibliothèque standard Python urllib."""
    req_data = json.dumps(data).encode("utf-8") if data else None
    req = urllib.request.Request(url, data=req_data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req) as response:
            body = response.read().decode("utf-8")
            return response.status, json.loads(body) if body else {}
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8")
        try:
            parsed = json.loads(body)
        except Exception:
            parsed = {"error": body}
        return e.code, parsed
    except Exception as e:
        return 0, {"error": str(e)}


def init_owner(email: str, password: str, supabase_url: str = None, service_key: str = None) -> None:
    env_vars = load_env_file()
    
    url = supabase_url or os.environ.get("SUPABASE_URL") or env_vars.get("SUPABASE_URL", "")
    key = service_key or os.environ.get("SUPABASE_SERVICE_ROLE_KEY") or env_vars.get("SUPABASE_SERVICE_ROLE_KEY", "")

    url = url.rstrip("/")
    if not url or not key:
        print("[-] Erreur : SUPABASE_URL ou SUPABASE_SERVICE_ROLE_KEY introuvable dans l'environnement ou .env.")
        print("    Vous pouvez aussi les passer directement en options :")
        print("    --supabase-url https://xyz.supabase.co --service-key <votre_cle_service_role>")
        sys.exit(1)

    print(f"[*] Initialisation du compte propriétaire pour {email} (UUID: {OWNER_UUID})...")

    headers = {
        "apikey": key,
        "Authorization": f"Bearer {key}",
        "Content-Type": "application/json",
    }

    # 1. Vérifier si l'utilisateur existe déjà
    get_url = f"{url}/auth/v1/admin/users/{OWNER_UUID}"
    status, res = api_request(get_url, "GET", headers)

    if status == 200 and res.get("id"):
        print(f"[+] L'utilisateur avec l'ID {OWNER_UUID} existe déjà ({res.get('email')}).")
        # Mise à jour email/password
        put_url = f"{url}/auth/v1/admin/users/{OWNER_UUID}"
        update_data = {"email": email, "password": password, "email_confirm": True}
        up_status, up_res = api_request(put_url, "PUT", headers, update_data)
        if up_status in (200, 204):
            print(f"[✓] Identifiants et mot de passe mis à jour avec succès pour {email}.")
        else:
            print(f"[!] Erreur de mise à jour : {up_res}")
        return

    # 2. Créer l'utilisateur avec l'UUID exact existant
    create_url = f"{url}/auth/v1/admin/users"
    create_data = {
        "id": OWNER_UUID,
        "email": email,
        "password": password,
        "email_confirm": True,
        "user_metadata": {
            "role": "owner",
            "display_name": email.split("@")[0]
        }
    }

    status, res = api_request(create_url, "POST", headers, create_data)

    if status in (200, 201) and res.get("id"):
        print(f"[✓] Succès ! Compte propriétaire créé avec succès.")
        print(f"    - Email : {res.get('email')}")
        print(f"    - UUID  : {res.get('id')}")
        print(f"[✓] Toutes vos playlists, favoris et fichiers existants sont désormais reliés à ce compte.")
    else:
        print(f"[-] Réponse Supabase : HTTP {status} - {res}")
        print("\n[!] Plan B (Si l'API restreint l'injection d'ID, exécuter cette requête dans Supabase SQL Editor) :")
        print(f"""
INSERT INTO auth.users (
    instance_id,
    id,
    aud,
    role,
    email,
    encrypted_password,
    email_confirmed_at,
    created_at,
    updated_at
) VALUES (
    '00000000-0000-0000-0000-000000000000',
    '{OWNER_UUID}',
    'authenticated',
    'authenticated',
    '{email}',
    crypt('{password}', gen_salt('bf')),
    NOW(),
    NOW(),
    NOW()
);
        """)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Initialiser le compte propriétaire Supabase Auth")
    parser.add_argument("--email", required=True, help="Adresse email du propriétaire")
    parser.add_argument("--password", required=True, help="Mot de passe du propriétaire")
    parser.add_argument("--supabase-url", required=False, help="URL de votre projet Supabase")
    parser.add_argument("--service-key", required=False, help="Clé SUPABASE_SERVICE_ROLE_KEY")
    args = parser.parse_args()

    init_owner(args.email, args.password, args.supabase_url, args.service_key)
