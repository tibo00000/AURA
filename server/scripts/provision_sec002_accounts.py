"""Prepared Auth Admin creation of exactly two NEW synthetic users in a named isolated project.

Default: offline checks only. --execute-isolated requires prior human authorization of that
project and these writes. Writes passwords/IDs ONLY into the protected local manifest.
Partial creation is recorded immediately; no blind retry or automatic broad cleanup.
"""
import argparse
import json
import logging
from pathlib import Path
import re
import secrets
import sys
import uuid


def validate_target(config,environment):
    ref=config["isolated_project_ref"]
    assert re.fullmatch("[a-z]{20}",ref)
    assert config["expected_project_host"] == ref+".supabase.co"
    assert config["supabase_url"] == "https://"+ref+".supabase.co"
    assert config["expected_project_host"] not in config["production_deny_hosts"]
    assert config["production_deny_hosts"] and config["confirmed_empty_isolated_environment"] is True
    assert config["users"] == []  # Never duplicate accounts on retry/partial failure.
    assert config.get("setup_state") in (None,"prepared")
    assert environment["SUPABASE_URL"] == config["supabase_url"]
    assert environment["SUPABASE_ANON_KEY"] == config["anon_key"] and config["anon_key"]
    assert environment["APP_ENV"] == "test" and environment["APP_DEBUG"].lower() == "false"
    assert all(environment[k].lower() == "false" for k in ("ENABLE_STARTUP_RECOVERY","ENABLE_CACHE_BACKFILL"))
    key=environment["SUPABASE_SERVICE_ROLE_KEY"]
    if not key.startswith("sb_secret_"):
        import jwt
        claims=jwt.decode(key,options={"verify_signature":False})
        assert claims["role"] == "service_role" and claims["ref"] == ref
    assert key


def create_accounts(config,client,persist):
    namespace=uuid.uuid4().hex
    config["setup_state"]="creating_accounts"
    persist(config)
    for label in ("a","b"):
        email="sec0023-"+label+"-"+namespace+"@example.com"
        password=secrets.token_urlsafe(32)
        result=client.auth.admin.create_user({"email":email,"password":password,"email_confirm":True})
        assert result.user and result.user.email == email
        config["users"].append({"email":email,"password":password,"expected_id":str(result.user.id)})
        persist(config)  # Preserve the exact account ledger after each success, including partial setup.
    config["setup_state"]="accounts_ready"
    persist(config)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest",type=Path,required=True)
    parser.add_argument("--backend-env",type=Path,required=True)
    parser.add_argument("--execute-isolated",action="store_true")
    args=parser.parse_args()
    previous_logging_level=logging.root.manager.disable
    count=0
    try:
        from dotenv import dotenv_values
        config=json.loads(args.manifest.read_text(encoding="utf-8-sig"))
        environment=dotenv_values(args.backend_env)
        validate_target(config,environment)
        if not args.execute_isolated:
            print('{"status":"creation_preflight_valid","network_accessed":false,"writes":false}')
            return 0
        logging.disable(logging.CRITICAL)
        import httpx
        from supabase import create_client,ClientOptions
        with httpx.Client(timeout=30,trust_env=False,follow_redirects=False) as transport:
            client=create_client(config["supabase_url"],environment["SUPABASE_SERVICE_ROLE_KEY"],
                options=ClientOptions(httpx_client=transport,persist_session=False,auto_refresh_token=False))
            def persist(value):
                args.manifest.write_text(json.dumps(value,indent=2)+"\n",encoding="utf-8")
            create_accounts(config,client,persist)
        print('{"status":"accounts_ready","accounts_created":2,"credentials_output":false}')
        return 0
    except Exception:
        if "config" in locals():count=len(config.get("users",[]))
        print(json.dumps({"status":"setup_failed","created_accounts_recorded":count,"details_redacted":True}))
        return 1
    finally:
        logging.disable(previous_logging_level)


if __name__ == "__main__":
    if not __debug__:
        print('{"status":"optimized_mode_refused"}');sys.exit(2)
    sys.exit(main())
