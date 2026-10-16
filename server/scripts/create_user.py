"""
Script d'administration CLI pour créer un compte utilisateur AURA (Supabase Auth).

Permet au propriétaire de créer des comptes pour ses amis en mode cercle privé
avec confirmation d'email automatique (email_confirm: True), sans nécessité de serveur SMTP.

Usage:
    python server/scripts/create_user.py --email ami@example.com --password motdepasse
"""

import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


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
    """Exécute une requête HTTP avec urllib."""
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


def create_user(
    email: str,
    password: str,
    role: str = "friend",
    supabase_url: str = None,
    service_key: str = None,
) -> None:
    env_vars = load_env_file()

    url = supabase_url or os.environ.get("SUPABASE_URL") or env_vars.get("SUPABASE_URL", "")
    key = service_key or os.environ.get("SUPABASE_SERVICE_ROLE_KEY") or env_vars.get("SUPABASE_SERVICE_ROLE_KEY", "")

    url = url.rstrip("/")
    if not url or not key:
        print("[-] Erreur : SUPABASE_URL ou SUPABASE_SERVICE_ROLE_KEY introuvable dans l'environnement ou .env.")
        sys.exit(1)

    trimmed_email = email.strip().lower()
    if not trimmed_email or "@" not in trimmed_email:
        print("[-] Erreur : Adresse email invalide.")
        sys.exit(1)

    if len(password) < 6:
        print("[-] Erreur : Le mot de passe doit contenir au moins 6 caractères.")
        sys.exit(1)

    print(f"[*] Création du compte AURA pour {trimmed_email}...")

    headers = {
        "apikey": key,
        "Authorization": f"Bearer {key}",
        "Content-Type": "application/json",
    }

    create_url = f"{url}/auth/v1/admin/users"
    create_data = {
        "email": trimmed_email,
        "password": password,
        "email_confirm": True,
        "user_metadata": {
            "role": role,
            "display_name": trimmed_email.split("@")[0],
        },
    }

    status, res = api_request(create_url, "POST", headers, create_data)

    if status in (200, 201) and res.get("id"):
        user_id = res.get("id")
        print(f"[✓] Compte créé avec succès !")
        print(f"    - Email   : {res.get('email')}")
        print(f"    - User ID : {user_id}")
        print(f"    - Rôle    : {role}")
        print(f"    - Statut  : Confirmé (email_confirm = True)")
        print(f"\nTon ami peut dès à présent se connecter sur l'application avec cet email et ce mot de passe.")
    else:
        err_msg = res.get("msg") or res.get("message") or res.get("error") or str(res)
        print(f"[-] Échec de création (HTTP {status}) : {err_msg}")
        sys.exit(1)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Créer un compte utilisateur Supabase Auth pour AURA")
    parser.add_argument("--email", required=True, help="Adresse email de l'utilisateur")
    parser.add_argument("--password", required=True, help="Mot de passe initial (min 6 caractères)")
    parser.add_argument("--role", default="friend", help="Rôle attribué (défaut: friend)")
    parser.add_argument("--supabase-url", required=False, help="URL du projet Supabase")
    parser.add_argument("--service-key", required=False, help="Clé SUPABASE_SERVICE_ROLE_KEY")
    args = parser.parse_args()

    create_user(
        email=args.email,
        password=args.password,
        role=args.role,
        supabase_url=args.supabase_url,
        service_key=args.service_key,
    )
