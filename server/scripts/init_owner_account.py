"""
Script d'initialisation du compte propriétaire Supabase Auth.

Crée ou met à jour le compte utilisateur Supabase Auth avec l'identifiant exact:
12345678-1234-1234-1234-1234567890ab
garantissant la préservation intégrale de toutes les playlists, favoris,
snapshots de lecture et fichiers Cloud existants.

Usage:
    python init_owner_account.py --email mon-email@example.com --password mon_mot_de_passe
"""

import argparse
import sys
from pathlib import Path

# Add server directory to path
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.config import get_settings
from app.db.supabase import supabase

OWNER_UUID = "12345678-1234-1234-1234-1234567890ab"


def init_owner(email: str, password: str) -> None:
    settings = get_settings()
    if not settings.supabase_url or not settings.supabase_service_role_key:
        print("[-] Erreur : SUPABASE_URL ou SUPABASE_SERVICE_ROLE_KEY manquant dans le fichier .env")
        sys.exit(1)

    print(f"[*] Initialisation du compte propriétaire pour {email} (UUID: {OWNER_UUID})...")

    try:
        # 1. Check if user already exists
        user_response = supabase.auth.admin.get_user_by_id(OWNER_UUID)
        if user_response and user_response.user:
            print(f"[+] L'utilisateur avec l'ID {OWNER_UUID} existe déjà dans Supabase Auth ({user_response.user.email}).")
            # Update password/email if requested
            update_attrs = {}
            if email and email != user_response.user.email:
                update_attrs["email"] = email
            if password:
                update_attrs["password"] = password
            if update_attrs:
                supabase.auth.admin.update_user_by_id(OWNER_UUID, update_attrs)
                print(f"[+] Identifiants mis à jour pour {OWNER_UUID}.")
            return

        # 2. Create user with the exact existing UUID
        create_res = supabase.auth.admin.create_user({
            "id": OWNER_UUID,
            "email": email,
            "password": password,
            "email_confirm": True,
            "user_metadata": {
                "role": "owner",
                "display_name": email.split("@")[0]
            }
        })

        if create_res and create_res.user:
            print(f"[✓] Succès ! Compte propriétaire créé avec succès.")
            print(f"    - Email : {create_res.user.email}")
            print(f"    - UUID  : {create_res.user.id}")
            print(f"[✓] Toutes vos données existantes sont désormais connectées à ce compte.")
        else:
            print(f"[-] Réponse inattendue lors de la création : {create_res}")
    except Exception as e:
        print(f"[-] Erreur lors de la création du compte propriétaire : {e}")
        print("\n[!] Plan B (Migration SQL manuelle si le SDK restreint l'ID) :")
        print(f"""
Exécutez cette requête dans le SQL Editor de votre Dashboard Supabase :

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
    args = parser.parse_args()

    init_owner(args.email, args.password)
