"""OFFLINE ONLY: stage an isolated test bundle. Never connects, starts Docker or provisions Auth.

Output must be a NEW operator-protected directory outside Git, with private Windows ACLs
already applied to its parent. Only new ephemeral TLS material is generated, no existing secret read.
"""
import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import ipaddress
import json
from pathlib import Path
import re
import sys

ROOT=Path(__file__).resolve().parents[2]
BASE_SQL=["server/app/db/migrations/004_create_sync_tables.sql",
          "server/app/db/migrations/003_create_download_jobs.sql",
          "server/app/db/migrations/001_create_catalog_tables.sql",
          "server/app/db/migrations/002_create_mapping_tables.sql",
          "server/migrations/001_enable_rls_policies.sql"]
MIGRATION="supabase/migrations/20261017200822_secure_user_data_isolation.sql"


def sql_bundle(root=ROOT):
    guard="""BEGIN;
SET LOCAL statement_timeout='60s';
DO $empty$ BEGIN
IF EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
 WHERE n.nspname='public' AND c.relkind IN ('r','p','v','m','f'))
 OR EXISTS (SELECT 1 FROM auth.users) THEN
 RAISE EXCEPTION 'Fresh empty isolated project required'; END IF;
END $empty$;
"""
    base=guard+"\n".join((root/p).read_text(encoding="utf-8-sig") for p in BASE_SQL)
    # Modern projects may have no automatic grants. No blanket grants to client roles.
    base+="\nGRANT USAGE ON SCHEMA public TO service_role;\nGRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO service_role;\nCOMMIT;\n"
    fixtures="""BEGIN;
INSERT INTO public.artists(id,display_artist_name,normalized_name) VALUES('sec0023-artist','Synthetic','synthetic');
INSERT INTO public.albums(id,display_album_title,normalized_title,artist_id) VALUES('sec0023-album','Synthetic','synthetic','sec0023-artist');
INSERT INTO public.tracks(id,display_title,normalized_title,album_id,artist_id,duration_ms)
 VALUES('sec0023-track','Silence','silence','sec0023-album','sec0023-artist',100);
COMMIT;
"""
    verify="""BEGIN READ ONLY;
SELECT json_build_object('version',current_setting('server_version'),
 'owner_guards',(SELECT count(*) FROM pg_policy WHERE polname='sec002_owner_guard' AND NOT polpermissive),
 'private_rls',(SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
 WHERE n.nspname='public' AND c.relrowsecurity AND c.relname IN
 ('profiles','user_settings','playlists','playlist_items','likes','playback_snapshots','history_items',
 'listening_sessions','playback_events','processed_operations','processed_batches')),
 'rpc_invoker',(SELECT NOT prosecdef FROM pg_proc WHERE oid='public.aura_mutate_playlist_items(text,text,text,jsonb,timestamptz)'::regprocedure),
 'rpc_auth',has_function_privilege('authenticated','public.aura_mutate_playlist_items(text,text,text,jsonb,timestamptz)','EXECUTE'),
 'rpc_anon',has_function_privilege('anon','public.aura_mutate_playlist_items(text,text,text,jsonb,timestamptz)','EXECUTE'),
 'schema_setting',current_setting('pgrst.db_schemas',true));
ROLLBACK;
"""
    return {"01-schema.sql":base.encode(),
            "02-catalog-rls.sql":(root/"supabase/migrations/20261016214500_enable_catalog_rls.sql").read_bytes(),
            "03-"+Path(MIGRATION).name:(root/MIGRATION).read_bytes(),
            "04-fixtures.sql":fixtures.encode(),"05-verify.sql":verify.encode()}


def create_tls(output):
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes,serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.x509.oid import NameOID
    key=ec.generate_private_key(ec.SECP256R1())
    name=x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,"AURA SEC0023 isolated loopback")])
    now=datetime.now(timezone.utc)
    cert=(x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
          .serial_number(x509.random_serial_number()).not_valid_before(now-timedelta(minutes=5))
          .not_valid_after(now+timedelta(days=7))
          .add_extension(x509.BasicConstraints(ca=True,path_length=0),critical=True)
          .add_extension(x509.SubjectAlternativeName([x509.DNSName("localhost"),x509.IPAddress(ipaddress.ip_address("127.0.0.1"))]),critical=False)
          .sign(key,hashes.SHA256()))
    (output/"loopback.crt").write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    private=output/"loopback.key"
    with private.open("xb") as stream:
        private.chmod(0o600)
        stream.write(key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()))


def stage(output,project_ref,production_hosts,root=ROOT):
    assert re.fullmatch("[a-z]{20}",project_ref)
    assert production_hosts and project_ref+".supabase.co" not in production_hosts
    output=output.resolve()
    assert not output.exists()
    assert not output.is_relative_to(root.resolve())  # Keep credentials/private keys OUTSIDE the repository.
    output.mkdir(mode=0o700)
    files=sql_bundle(root)
    for name,content in files.items():(output/name).write_bytes(content)
    create_tls(output)
    config={"purpose":"aura-sec0021-isolated","confirmed_empty_isolated_environment":False,
            "isolated_project_ref":project_ref,"expected_project_host":project_ref+".supabase.co",
            "production_deny_hosts":production_hosts,"api_url":"https://127.0.0.1:18443",
            "supabase_url":"https://"+project_ref+".supabase.co","anon_key":"","users":[],
            "test_track_id":"sec0023-track","api_ca_file":str(output/"loopback.crt"),
            "expected_jwt_algorithm":"ES256","max_expiry_wait_seconds":360,
            "sql_control_file":str(output/"sql-control.json")}
    (output/"manifest.json").write_text(json.dumps(config,indent=2)+"\n")
    sql={"host":"db."+project_ref+".supabase.co","port":5432,"dbname":"postgres","user":"postgres",
         "password":"","sslrootcert":""}
    (output/"sql-control.json").write_text(json.dumps(sql,indent=2)+"\n")
    template=(root/"server/ops/sec0023/backend.env_example").read_text()
    (output/"backend.env").write_text(template.replace("<ISOLATED_PROJECT_REF>",project_ref))
    compose=(root/"server/ops/sec0023/compose.yml").read_text().replace("<APP_SOURCE>",json.dumps(str(root/"server/app").replace("\\","/")))
    (output/"compose.yml").write_text(compose)
    hashes={name:hashlib.sha256(data).hexdigest() for name,data in files.items()}
    (output/"bundle-hashes.json").write_text(json.dumps(hashes,indent=2)+"\n")
    return hashes


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output",type=Path,required=True)
    parser.add_argument("--project-ref",required=True)
    parser.add_argument("--production-host",action="append",required=True)
    parser.add_argument("--protected-parent-confirmed",action="store_true",required=True)
    args=parser.parse_args()
    try:
        hashes=stage(args.output,args.project_ref,args.production_host)
        print(json.dumps({"status":"staged_offline","sql_sha256":hashes,"network_accessed":False,"remote_writes":False}))
        return 0
    except Exception:
        print(json.dumps({"status":"staging_failed","details_redacted":True,"network_accessed":False}))
        return 2


if __name__ == "__main__":
    if not __debug__:
        print('{"status":"optimized_mode_refused"}');sys.exit(2)
    sys.exit(main())
