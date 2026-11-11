"""Offline safety gates for prepared operational tooling; no project/credential/network access."""
import importlib.util
import json
import os
from pathlib import Path
import re
import sys

import pytest

ROOT = Path(os.environ.get("AURA_REPOSITORY_ROOT",Path(__file__).resolve().parents[2]))


def module(name):
    spec = importlib.util.spec_from_file_location(name,ROOT/"server/scripts"/(name+".py"))
    loaded = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(loaded)
    return loaded


checker = module("check_sec002_inventory")
integrated = module("verify_sec002_integrated")


@pytest.fixture
def config():
    return {"purpose":"aura-sec0021-isolated","confirmed_empty_isolated_environment":True,
            "production_deny_hosts":["production.example.invalid"],"expected_project_host":"isolated.example.invalid",
            "supabase_url":"https://isolated.example.invalid","api_url":"https://localhost:18443",
            "anon_key":"sb_publishable_SYNTHETIC","test_track_id":"synthetic-shared",
            "users":[{"email":"a@example.invalid","password":"SYNTHETIC_ONLY","expected_id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"},
                     {"email":"b@example.invalid","password":"SYNTHETIC_ONLY","expected_id":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"}]}


def test_integrated_default_only_checks_config(tmp_path,monkeypatch,capsys,config):
    path=tmp_path/"synthetic.json"
    path.write_text(json.dumps(config))
    monkeypatch.setattr(sys,"argv",["prepared","--manifest",str(path)])
    monkeypatch.setattr(integrated,"execute",lambda _:pytest.fail("network execution without flag"))
    assert integrated.main() == 0
    output=json.loads(capsys.readouterr().out)
    assert output == {"status":"config_valid","network_accessed":False,"writes":False}


@pytest.mark.parametrize("field,value",[
    ("purpose","production"),("confirmed_empty_isolated_environment",False),
    ("supabase_url","https://production.example.invalid"),("supabase_url","http://isolated.example.invalid"),
    ("api_url","https://name:password@localhost:18443"),("api_url","https://localhost:18443?token=SYNTHETIC"),
    ("production_deny_hosts",[]),("anon_key","sb_secret_SYNTHETIC")])
def test_integrated_config_rejects_unsafe_target_or_key(config,field,value):
    config[field]=value
    with pytest.raises(AssertionError):
        integrated.validate_config(config)


def test_inventory_never_grants_automatic_deployment_go():
    result=checker.assess({})
    assert result["status"] == "NO_GO"
    assert "aggregate_counts_not_collected" in result["blocks"]
    assert result["remote_write_authorized"] is False


def test_inventory_blocks_privileged_roles_and_definers():
    metadata={"roles":[{"name":"anon","bypassrls":True}],
              "functions":[{"name":"synthetic","definer":True,"result":"integer","anon_execute":True}]}
    blocks=checker.assess(metadata)["blocks"]
    assert "privileged_client_role:anon" in blocks
    assert "exposed_definer_requires_review:synthetic" in blocks


@pytest.mark.parametrize("filename",["sec002_inventory_metadata.sql","sec002_inventory_counts.sql","sec002_inventory_batches.sql"])
def test_inventory_scripts_only_transaction_control_and_select(filename):
    text=(ROOT/"supabase/sql"/filename).read_text()
    stripped=re.sub(r"--[^\n]*","",text)
    stripped=re.sub(r"'(?:''|[^'])*'","''",stripped)
    statements=[s.strip().upper() for s in stripped.split(";") if s.strip()]
    assert statements[0] == "BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY"
    assert statements[-1] == "ROLLBACK"
    assert all(s.startswith(("BEGIN TRANSACTION","SET LOCAL ","SELECT ","ROLLBACK")) for s in statements)
    assert not re.search(r"\b(INSERT|UPDATE|DELETE|CREATE|ALTER|DROP|CALL|DO|COPY)\b",stripped.upper())
