"""Offline preparation tests. No real Supabase, account or session is used."""
import copy
import importlib.util
import json
import logging
import os
from pathlib import Path
import sys
from types import SimpleNamespace

import pytest

ROOT=Path(os.environ.get("AURA_REPOSITORY_ROOT",Path(__file__).resolve().parents[2]))
sys.path.insert(0,str(ROOT/"server/scripts"))


def load(name):
    spec=importlib.util.spec_from_file_location(name,ROOT/"server/scripts"/(name+".py"))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    return module


runner=load("verify_sec002_integrated")
prepare=load("prepare_sec002_environment")
sql_control=load("verify_sec002_sql_controls")
accounts=load("provision_sec002_accounts")


class PullScenario:
    def __init__(self,mode):
        self.mode=mode;self.names=["original-a","original-b"];self.calls=[]
    def __call__(self,i,method,path,body):
        self.calls.append((i,method,path,body))
        if path.endswith("bootstrap"):
            return {"sync_token":{"value":"st_REAL_RETURNED_"+str(i),"issued_at":"2026-01-01T00:00:00+00:00"}}
        if method == "PATCH":
            self.names[i]=body["name"]
            return {"name":self.names[i],"updated_at":"2026-01-01T00:00:01+00:00"}
        assert body["since_token"] == "st_REAL_RETURNED_"+str(i)
        changes=[{"entity_type":"playlist","entity_id":["own-a","own-b"][i],"change_type":"upsert","payload":{"name":self.names[i]}}]
        if self.mode == "empty":changes=[]
        if self.mode == "foreign":changes.append({"entity_type":"playlist","entity_id":["own-b","own-a"][i],"payload":{"name":self.names[1-i]}})
        if self.mode == "stale":changes[0]["payload"]["name"]="original"
        return {"changes":changes,"has_more":self.mode == "pagination","next_pull_token":{"value":"st_NEXT_RETURNED"}}


def test_positive_pull_requires_an_actual_later_owner_delta():
    scenario=PullScenario("valid")
    runner.verify_positive_pull(scenario,"device",["own-a","own-b"],"marker")
    assert [c[1] for c in scenario.calls] == ["POST","POST","PATCH","PATCH","POST","POST"]


@pytest.mark.parametrize("mode",["empty","foreign","stale","pagination"])
def test_positive_pull_rejects_empty_leak_stale_and_incomplete_results(mode):
    with pytest.raises(AssertionError):
        runner.verify_positive_pull(PullScenario(mode),"device",["own-a","own-b"],"marker")


@pytest.fixture
def target(tmp_path):
    ca=tmp_path/"root.crt";ca.write_text("SYNTHETIC_PUBLIC_ROOT")
    sql=tmp_path/"sql.json"
    value={"host":"db.abcdefghijklmnopqrst.supabase.co","port":5432,"user":"postgres","dbname":"postgres",
           "password":"SYNTHETIC_ONLY","sslrootcert":str(ca)}
    sql.write_text(json.dumps(value))
    return {"isolated_project_ref":"abcdefghijklmnopqrst","expected_project_host":"abcdefghijklmnopqrst.supabase.co",
            "production_deny_hosts":["production.example.invalid"],"sql_control_file":str(sql)},value


def test_sql_connection_target_is_pinned_and_tls_mandatory(target):
    config,_=target
    result=sql_control.connection_options(config)
    assert result["sslmode"] == "verify-full" and result["connect_timeout"] == 10


@pytest.mark.parametrize("start,wait",[(99,False),(100.8,True)])
def test_real_session_preflight_waits_only_for_small_clock_skew(monkeypatch,start,wait):
    import jwt
    sleeps=[]
    monkeypatch.setattr(jwt,"get_unverified_header",lambda token:{"alg":"ES256"})
    monkeypatch.setattr(jwt,"decode",lambda *a,**k:{"iss":"https://test.supabase.co/auth/v1","exp":400,"iat":start})
    monkeypatch.setattr(runner.time,"time",lambda:100)
    monkeypatch.setattr(runner.time,"sleep",sleeps.append)
    runner.validate_live_session(SimpleNamespace(access_token="SYNTHETIC"),{"supabase_url":"https://test.supabase.co"})
    assert bool(sleeps) is wait
    if wait: assert sleeps[0] == pytest.approx(.9)


def test_real_session_preflight_rejects_large_clock_skew(monkeypatch):
    import jwt
    monkeypatch.setattr(jwt,"get_unverified_header",lambda token:{"alg":"ES256"})
    monkeypatch.setattr(jwt,"decode",lambda *a,**k:{"iss":"https://test.supabase.co/auth/v1","exp":400,"iat":103})
    monkeypatch.setattr(runner.time,"time",lambda:100)
    monkeypatch.setattr(runner.time,"sleep",lambda seconds:pytest.fail("Unexpected wait"))
    with pytest.raises(AssertionError):
        runner.validate_live_session(SimpleNamespace(access_token="SYNTHETIC"),{"supabase_url":"https://test.supabase.co"})


@pytest.mark.parametrize("field,value",[("host","production.example.invalid"),("host","127.0.0.1"),
    ("host","db.zyxwvutsrqponmlkjihg.supabase.co"),("port",6543),("dbname","other"),("user","arbitrary")])
def test_sql_control_refuses_wrong_or_ambiguous_project(target,field,value):
    config,connection=target;connection[field]=value
    Path(config["sql_control_file"]).write_text(json.dumps(connection))
    with pytest.raises(AssertionError):sql_control.connection_options(config)


def test_session_pooler_requires_project_specific_username(target):
    config,connection=target
    connection.update(host="aws-0-eu-west-1.pooler.supabase.com",user="postgres.abcdefghijklmnopqrst")
    Path(config["sql_control_file"]).write_text(json.dumps(connection))
    assert sql_control.connection_options(config)["user"].endswith(config["isolated_project_ref"])
    connection["user"]="postgres.zyxwvutsrqponmlkjihg"
    Path(config["sql_control_file"]).write_text(json.dumps(connection))
    with pytest.raises(AssertionError):sql_control.connection_options(config)


def test_bundle_preserves_exact_reviewed_migration_bytes_and_no_anonymous_grants():
    bundle=prepare.sql_bundle(ROOT)
    assert bundle["03-20261017200822_secure_user_data_isolation.sql"] == (ROOT/prepare.MIGRATION).read_bytes()
    assert b"Fresh empty isolated project required" in bundle["01-schema.sql"]
    assert b"TO anon,authenticated" not in bundle["01-schema.sql"]
    assert b"ROLLBACK" in bundle["05-verify.sql"]


def test_generated_tls_is_process_local_and_matches_loopback(tmp_path):
    from cryptography import x509
    from cryptography.hazmat.primitives.serialization import load_pem_private_key
    prepare.create_tls(tmp_path)
    cert=x509.load_pem_x509_certificate((tmp_path/"loopback.crt").read_bytes())
    private=load_pem_private_key((tmp_path/"loopback.key").read_bytes(),None)
    assert cert.public_key().public_numbers() == private.public_key().public_numbers()
    assert "localhost" in cert.extensions.get_extension_for_class(x509.SubjectAlternativeName).value.get_values_for_type(x509.DNSName)
    context=runner.api_tls_context({"api_ca_file":str(tmp_path/"loopback.crt")})
    assert context.check_hostname


def test_staging_refuses_repo_output_and_existing_directory(tmp_path):
    with pytest.raises(AssertionError):prepare.stage(ROOT/"should-not-exist","abcdefghijklmnopqrst",["production.invalid"])
    with pytest.raises(AssertionError):prepare.stage(tmp_path,"abcdefghijklmnopqrst",["production.invalid"])


def test_staging_creates_only_new_offline_assets_and_disabled_manifest(tmp_path):
    out=tmp_path/"new"
    hashes=prepare.stage(out,"abcdefghijklmnopqrst",["production.invalid"])
    config=json.loads((out/"manifest.json").read_text())
    assert config["users"] == [] and config["anon_key"] == ""
    assert config["confirmed_empty_isolated_environment"] is False
    assert len(hashes) == 5 and (out/"loopback.key").exists()
    assert '<APP_SOURCE>' not in (out/"compose.yml").read_text()
    with pytest.raises(AssertionError):runner.validate_config(config)


def test_partial_account_creation_is_recorded_and_not_retried():
    created=[];saved=[]
    def create(data):
        if created:raise RuntimeError("synthetic second-account failure")
        created.append(data)
        return SimpleNamespace(user=SimpleNamespace(id="synthetic-a",email=data["email"]))
    config={"users":[]}
    client=SimpleNamespace(auth=SimpleNamespace(admin=SimpleNamespace(create_user=create)))
    with pytest.raises(RuntimeError):accounts.create_accounts(config,client,lambda c:saved.append(copy.deepcopy(c)))
    assert len(saved[-1]["users"]) == 1 and saved[-1]["setup_state"] == "creating_accounts"
    assert created[0]["email_confirm"] is True
    assert len(created[0]["password"]) >= 32


def test_provision_default_is_offline_and_does_not_publish_credentials(tmp_path,monkeypatch,capsys):
    config={"isolated_project_ref":"abcdefghijklmnopqrst","expected_project_host":"abcdefghijklmnopqrst.supabase.co",
        "supabase_url":"https://abcdefghijklmnopqrst.supabase.co","production_deny_hosts":["production.invalid"],
        "confirmed_empty_isolated_environment":True,"users":[],"anon_key":"sb_publishable_SYNTHETIC"}
    manifest=tmp_path/"manifest.json";manifest.write_text(json.dumps(config))
    env=tmp_path/"backend.env";env.write_text("SUPABASE_URL="+config["supabase_url"]+"\nSUPABASE_ANON_KEY=sb_publishable_SYNTHETIC\nSUPABASE_SERVICE_ROLE_KEY=sb_secret_SYNTHETIC\nAPP_ENV=test\nAPP_DEBUG=false\nENABLE_STARTUP_RECOVERY=false\nENABLE_CACHE_BACKFILL=false\n")
    monkeypatch.setattr(sys,"argv",["prepared","--manifest",str(manifest),"--backend-env",str(env)])
    previous=logging.root.manager.disable
    assert accounts.main() == 0
    assert logging.root.manager.disable == previous
    output=capsys.readouterr().out
    assert "SYNTHETIC" not in output and json.loads(output)["network_accessed"] is False
