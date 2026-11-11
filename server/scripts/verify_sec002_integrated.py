"""Prepared only: real Auth -> FastAPI -> SDK -> PostgREST -> PostgreSQL in an ISOLATED project.

Never run against production. Default validates a protected manifest without any network.
Execution additionally requires --execute-isolated after explicit authorization of test writes.
No accounts, migrations or catalog data are created by this runner. No token is printed/saved.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import json
import logging
import math
import re
import io
import ssl
import time
import wave
from pathlib import Path
from urllib.parse import urlsplit
import uuid


def validate_config(config):
    assert config.get("purpose") == "aura-sec0021-isolated"
    assert config.get("confirmed_empty_isolated_environment") is True
    deny = config["production_deny_hosts"]
    assert isinstance(deny,list) and deny and all(isinstance(h,str) and h for h in deny)
    for key in ("api_url", "supabase_url"):
        u = urlsplit(config[key])
        assert u.scheme == "https" and u.hostname and not u.username and not u.password
        assert not u.query and not u.fragment and u.hostname not in deny
        assert not u.path or u.path == "/"
    assert urlsplit(config["supabase_url"]).hostname == config["expected_project_host"]
    assert config["anon_key"] and config["test_track_id"]
    assert not config["anon_key"].startswith("sb_secret_")
    if not config["anon_key"].startswith("sb_publishable_"):
        import jwt
        assert jwt.decode(config["anon_key"],options={"verify_signature":False})["role"] == "anon"
    assert len(config["users"]) == 2
    assert len({u["expected_id"] for u in config["users"]}) == 2
    for user in config["users"]:
        uuid.UUID(user["expected_id"])
        assert user["email"] and user["password"]
    assert 0 < config.get("max_expiry_wait_seconds",360) <= 600
    assert config.get("expected_jwt_algorithm","ES256") in ("ES256","RS256","HS256")
    if config.get("api_ca_file"):
        assert Path(config["api_ca_file"]).is_file()


def api_tls_context(config):
    # Retain public roots for Supabase; add only the ephemeral local CA for this process.
    context=ssl.create_default_context()
    if config.get("api_ca_file"):
        context.load_verify_locations(cafile=config["api_ca_file"])
    return context


def validate_execution(config):
    """Extra gates before ANY network or write; legacy config-only mode stays offline."""
    ref=config["isolated_project_ref"]
    assert re.fullmatch("[a-z]{20}",ref)
    assert config["expected_project_host"] == ref+".supabase.co"
    assert urlsplit(config["api_url"]).hostname in ("localhost","127.0.0.1")
    assert config.get("api_ca_file")
    from verify_sec002_sql_controls import connection_options
    connection_options(config)
    import psycopg  # Tool-only dependency; refuse before creating fixtures if unavailable.


def validate_api_contract(document):
    """Check the real target's advertised routes/schemas BEFORE Auth or test writes."""
    routes = {"/me/playlists":("get","post"),"/me/playlists/{id}":("patch","delete"),
              "/me/playlists/{id}/tracks":("post",),"/me/playlists/{id}/tracks/{trackId}":("delete",),
              "/me/sync/bootstrap":("post",),"/me/sync/push-batch":("post",),"/me/sync/pull-batch":("post",),
              "/me/sync/files/{track_id}":("post","get","delete")}
    paths=document.get("paths",{})
    def resolve(schema):
        if "$ref" in schema:
            return document["components"]["schemas"][schema["$ref"].rsplit("/",1)[-1]]
        return schema
    for path,methods in routes.items():
        for method in methods:
            success="201" if path == "/me/sync/files/{track_id}" and method == "post" else "200"
            if method not in paths.get(path,{}) or success not in paths[path][method].get("responses",{}):
                raise ValueError("Required API contract unavailable")
    response=resolve(paths["/me/playlists"]["get"]["responses"]["200"]["content"]["application/json"]["schema"])
    data=response["properties"]["data"]
    if not any(resolve(s).get("type") == "array" for s in data.get("anyOf",[data])):
        raise ValueError("Expected playlist list envelope")
    for path,fields in {"/me/sync/bootstrap":{"device_id"},
        "/me/sync/push-batch":{"device_id","batch_id","sent_at","operations"},
        "/me/sync/pull-batch":{"device_id","since_token"},
        "/me/playlists":{"id","name"},"/me/playlists/{id}/tracks":{"id","track_id","position"}}.items():
        schema=resolve(paths[path]["post"]["requestBody"]["content"]["application/json"]["schema"])
        if not fields.issubset(schema.get("properties",{})):
            raise ValueError("Required request schema unavailable")
    upload=resolve(paths["/me/sync/files/{track_id}"]["post"]["requestBody"]["content"]["multipart/form-data"]["schema"])
    if "file" not in upload.get("properties",{}):
        raise ValueError("Required audio upload schema unavailable")


def owned_playlist(playlists, own_id, foreign_id):
    if not isinstance(playlists,list) or any(p["id"] == foreign_id for p in playlists):
        raise ValueError("Unexpected playlist isolation result")
    own=[p for p in playlists if p["id"] == own_id]
    if len(own) != 1:
        raise ValueError("Own playlist missing or duplicated")
    return own[0]


def pull_request(device, bootstrap):
    token=bootstrap["sync_token"]["value"]
    if not isinstance(token,str) or not token.startswith("st_") or len(token) <= 3:
        raise ValueError("Invalid returned synchronization token")
    return {"device_id":device,"since_token":token}


def verify_positive_pull(api,device,ids,marker):
    """A 200/empty pull is insufficient: prove a later owner's mutation actually arrives."""
    snapshots=[api(i,"POST","/me/sync/bootstrap",{"device_id":device}) for i in (0,1)]
    names=["A_DELTA_"+marker,"B_DELTA_"+marker]
    for i in (0,1):
        changed=api(i,"PATCH","/me/playlists/"+ids[i],{"name":names[i]})
        assert changed["name"] == names[i]
        assert datetime.fromisoformat(changed["updated_at"]) > datetime.fromisoformat(snapshots[i]["sync_token"]["issued_at"])
    for i in (0,1):
        request={**pull_request(device,snapshots[i]),"entity_types":["playlist"],"limit":200}
        pulled=api(i,"POST","/me/sync/pull-batch",request)
        assert pulled["has_more"] is False
        own=[c for c in pulled["changes"] if c["entity_type"] == "playlist" and c["entity_id"] == ids[i]]
        assert len(own) == 1 and own[0]["change_type"] == "upsert" and own[0]["payload"]["name"] == names[i]
        serialized=json.dumps(pulled)
        assert ids[1-i] not in serialized and names[1-i] not in serialized
        pull_request(device,{"sync_token":pulled["next_pull_token"]})


def require_denial(call,codes):
    from postgrest.exceptions import APIError
    try:
        call()
    except APIError as error:
        assert str(error.code) in codes
    else:
        raise AssertionError("Expected database refusal")


def synthetic_audio():
    content=io.BytesIO()
    with wave.open(content,"wb") as audio:
        audio.setnchannels(1);audio.setsampwidth(2);audio.setframerate(8000)
        audio.writeframes(b"\x00\x00"*800)
    return content.getvalue()


def validate_live_session(session,config):
    import jwt
    assert jwt.get_unverified_header(session.access_token)["alg"] == config.get("expected_jwt_algorithm","ES256")
    # Scheduling/shape preflight only. FastAPI must independently verify the signature/claims.
    claims=jwt.decode(session.access_token,options={"verify_signature":False})
    assert claims["iss"] == config["supabase_url"].rstrip("/")+"/auth/v1"
    assert 0 < claims["exp"]-time.time() <= config.get("max_expiry_wait_seconds",360)
    # Schedule only: never relax the backend verifier or accept a future token as valid.
    start=max(claims.get("iat",0),claims.get("nbf",0))
    assert isinstance(start,(int,float)) and not isinstance(start,bool) and math.isfinite(start)
    delay=start-time.time()
    assert delay <= 2, "Test environment clock skew exceeds two seconds"
    if delay > 0:
        time.sleep(delay+.1)


def execute(config):
    import httpx
    import jwt
    from supabase import create_client, ClientOptions
    logging.disable(logging.CRITICAL)
    clients, sessions, passed = [], [], []
    marker = uuid.uuid4().hex
    device = "sec0021-"+marker
    urls = config["api_url"].rstrip("/")
    names = ["A_PRIVATE_"+marker, "B_PRIVATE_"+marker]
    ids = ["sec0021-a-"+marker, "sec0021-b-"+marker]
    items = ["sec0021-ia-"+marker,"sec0021-ib-"+marker]
    track = config["test_track_id"]
    step = "initialization"
    try:
        with httpx.Client(timeout=30, follow_redirects=False, trust_env=False,verify=api_tls_context(config)) as http:
            step = "api_contract"
            response = http.get(urls+"/openapi.json")
            assert response.status_code == 200
            validate_api_contract(response.json())
            passed.append(step)
            step = "sql_readonly_preflight"
            from verify_sec002_sql_controls import verify_database
            verify_database(config)
            passed.append(step)
            def api(index, method, path, body=None, expected=200):
                response = http.request(method,urls+path,json=body,
                                        headers={"Authorization":"Bearer "+sessions[index].access_token})
                assert response.status_code == expected
                result = response.json()
                if index == 0:
                    assert names[1] not in json.dumps(result)
                return result.get("data")
            def op(kind, eid, action, payload, operation_id=None):
                return {"operation_id":operation_id or uuid.uuid4().hex,"entity_type":kind,"entity_id":eid,
                        "operation_type":action,"device_id":device,"occurred_at":datetime.now(timezone.utc).isoformat(),
                        "payload":payload}
            def push(index, operations, batch=None):
                return api(index,"POST","/me/sync/push-batch",{"device_id":device,"batch_id":batch or uuid.uuid4().hex,
                           "sent_at":datetime.now(timezone.utc).isoformat(),"operations":operations})
            for i,user in enumerate(config["users"]):
                step = "login_"+str(i)
                transport = httpx.Client(timeout=30, follow_redirects=False, trust_env=False)
                client = create_client(config["supabase_url"],config["anon_key"],options=ClientOptions(
                    persist_session=False, auto_refresh_token=False, httpx_client=transport))
                clients.append((client,transport))
                auth = client.auth.sign_in_with_password({"email":user["email"],"password":user["password"]})
                assert auth.user.id == user["expected_id"] and auth.session
                sessions.append(auth.session)
                validate_live_session(auth.session,config)
                api(i,"POST","/me/sync/bootstrap",{"device_id":device})
                # Signature acceptance is established by FastAPI, not by decoding this header.
                assert jwt.get_unverified_header(auth.session.access_token)["alg"] == config.get("expected_jwt_algorithm","ES256")
                api(i,"POST","/me/playlists",{"id":ids[i],"name":names[i]})
                api(i,"POST","/me/playlists/"+ids[i]+"/tracks",{"id":items[i],"track_id":track,"position":0})
                passed.append("real_login_bootstrap_owner_create_"+str(i))
            step = "rest_foreign_resource"
            a = owned_playlist(api(0,"GET","/me/playlists"),ids[0],ids[1])
            owned_playlist(api(1,"GET","/me/playlists"),ids[1],ids[0])
            for method,path,body in [("PATCH","/me/playlists/"+ids[1],{"name":"unauthorized"}),
                ("DELETE","/me/playlists/"+ids[1],None),
                ("POST","/me/playlists/"+ids[1]+"/tracks",{"id":"foreign-"+marker,"track_id":track,"position":1}),
                ("DELETE","/me/playlists/"+ids[1]+"/tracks/"+track,None)]:
                api(0,method,path,body,404)
            passed.append(step)
            step = "batch_foreign_and_mixed_ids"
            token = "ord_"+hashlib.md5(a["updated_at"].encode()).hexdigest()[:8]
            attacks = [op("playlist",ids[1],"update",{"name":"unauthorized"}),
                       op("playlist_item",items[1],"insert",{"playlist_id":ids[0],"track_id":track,"position":1}),
                       op("playlist_reorder",ids[0],"reorder",{"base_order_token":token,"items":[
                           {"playlist_item_id":items[0],"position":1},{"playlist_item_id":items[1],"position":0}]})]
            assert all(r["status"] == "conflict" for r in push(0,attacks)["results"])
            passed.append(step)
            step = "sessions_history_events"
            sid,hid,eid = [k+marker for k in ("session-","history-","event-")]
            legitimate = [op("listening_session",sid,"upsert",{"started_at":datetime.now(timezone.utc).isoformat()}),
                          op("history_item",hid,"insert",{"track_id":track,"listening_session_id":sid}),
                          op("playback_event",eid,"insert",{"track_id":track,"session_id":sid,"event_type":"play"})]
            assert all(r["status"] in ("applied","merged") for r in push(1,legitimate)["results"])
            assert push(0,[op("listening_session","session-a"+marker,"upsert",{
                "started_at":datetime.now(timezone.utc).isoformat()})])["results"][0]["status"] == "applied"
            foreign = [op("listening_session",sid,"upsert",{"total_listening_ms":999}),
                       op("history_item",hid,"insert",{"track_id":track}),
                       op("playback_event",eid,"insert",{"track_id":track,"session_id":sid,"event_type":"play"}),
                       op("playback_event","bad-parent-"+marker,"insert",{"track_id":track,"session_id":sid,"event_type":"play"})]
            assert all(r["status"] == "conflict" for r in push(0,foreign)["results"])
            passed.append(step)
            step = "direct_sdk_rls"
            ca,cb = clients[0][0],clients[1][0]
            assert ca.table("playlists").select("id").eq("id",ids[1]).execute().data == []
            assert ca.table("playlists").update({"name":"unauthorized"}).eq("id",ids[1]).execute().data == []
            assert ca.table("playlists").delete().eq("id",ids[1]).execute().data == []
            assert cb.table("playlists").select("name").eq("id",ids[1]).execute().data[0]["name"] == names[1]
            passed.append(step)
            step = "bootstrap_pull_and_concurrency"
            def read(i):
                snapshot = api(i,"POST","/me/sync/bootstrap",{"device_id":device})
                playlist_ids = {p["id"] for p in snapshot["snapshot"]["playlists"]}
                assert ids[i] in playlist_ids and ids[1-i] not in playlist_ids
                result = api(i,"POST","/me/sync/pull-batch",pull_request(device,snapshot))
                assert names[1-i] not in json.dumps(result)
            with ThreadPoolExecutor(max_workers=2) as pool:
                list(pool.map(read,[0,1]))
            passed.append(step)
            step = "owner_idempotence"
            shared_op,shared_batch = uuid.uuid4().hex,uuid.uuid4().hex
            for i in (0,1):
                operations=[op("playlist",ids[i],"update",{"name":names[i]},shared_op)]
                assert push(i,operations,shared_batch)["results"][0]["status"] in ("applied","merged")
                assert push(i,operations,shared_batch)["results"][0]["status"] == "ignored_duplicate"
            passed.append(step)
            step = "data_api_schema_rpc_and_upserts"
            for i,(client,_) in enumerate(clients):
                other=1-i
                public=http.get(config["supabase_url"].rstrip("/")+"/rest/v1/playlists?select=id&limit=0",
                    headers={"apikey":config["anon_key"],"Authorization":"Bearer "+sessions[i].access_token,"Accept-Profile":"public"})
                assert public.status_code == 200
                hidden=http.get(config["supabase_url"].rstrip("/")+"/rest/v1/users?select=id&limit=0",
                    headers={"apikey":config["anon_key"],"Authorization":"Bearer "+sessions[i].access_token,"Accept-Profile":"auth"})
                assert hidden.status_code == 406
                require_denial(lambda:client.rpc("aura_mutate_playlist_items",{
                    "p_playlist_id":ids[other],"p_item_id":None,"p_action":"rest_patch",
                    "p_payload":{"name":"unauthorized","user_id":config["users"][i]["expected_id"]}}).execute(),{"42501"})
                own=client.rpc("aura_mutate_playlist_items",{"p_playlist_id":ids[i],"p_item_id":None,
                    "p_action":"rest_patch","p_payload":{"is_pinned":True}}).execute().data
                assert own["entity"]["id"] == ids[i] and own["entity"]["is_pinned"] is True
                own_upsert=client.table("playlists").upsert({"id":ids[i],"user_id":config["users"][i]["expected_id"],"name":names[i]}).execute().data
                assert len(own_upsert) == 1 and own_upsert[0]["id"] == ids[i]
                require_denial(lambda:client.table("playlists").upsert({"id":ids[other],
                    "user_id":config["users"][i]["expected_id"],"name":"unauthorized"}).execute(),{"42501","23505"})
                # Parent B FK with an otherwise valid A row, through real PostgREST.
                require_denial(lambda:client.table("playback_events").insert({"id":"bad-fk-"+str(i)+marker,
                    "user_id":config["users"][i]["expected_id"],"session_id":("session-" if i == 0 else "session-a")+marker,
                    "track_id":track,"event_type":"play"}).execute(),{"42501","23503"})
                require_denial(lambda:client.table("tracks").select("id").limit(1).execute(),{"42501"})
                before=owned_playlist(api(i,"GET","/me/playlists"),ids[i],ids[other])
                api(i,"POST","/me/playlists",{"id":ids[other],"name":"unauthorized"},500)
                api(i,"POST","/me/playlists/"+ids[i]+"/tracks",{"id":items[other],"track_id":track,"position":10},500)
                api(i,"POST","/me/playlists/"+ids[i]+"/tracks",{"id":items[i],"track_id":track,"position":10},500)
                api(i,"POST","/me/playlists/"+ids[i]+"/tracks",{"id":"occupied-"+str(i)+marker,"track_id":track,"position":0},500)
                api(i,"PATCH","/me/playlists/"+ids[other],{"name":"unauthorized"},404)
                api(i,"DELETE","/me/playlists/"+ids[other],None,404)
                api(i,"POST","/me/playlists/"+ids[other]+"/tracks",{"id":"bad-"+str(i)+marker,"track_id":track,"position":10},404)
                api(i,"DELETE","/me/playlists/"+ids[other]+"/tracks/"+track,None,404)
                assert owned_playlist(api(i,"GET","/me/playlists"),ids[i],ids[other]) == before
                extra="swap-"+str(i)+marker
                api(i,"POST","/me/playlists/"+ids[i]+"/tracks",{"id":extra,"track_id":track,"position":1})
                for order in ((1,0),(0,1)):
                    current=owned_playlist(api(i,"GET","/me/playlists"),ids[i],ids[other])
                    result=client.rpc("aura_mutate_playlist_items",{"p_playlist_id":ids[i],"p_item_id":None,
                        "p_action":"reorder","p_payload":{"items":[{"playlist_item_id":items[i],"position":order[0]},
                        {"playlist_item_id":extra,"position":order[1]}]},"p_expected_updated_at":current["updated_at"]}).execute().data
                    assert result["status"] == "applied"
                    changed=owned_playlist(api(i,"GET","/me/playlists"),ids[i],ids[other])
                    assert {v["id"]:v["position"] for v in changed["items"]} == {items[i]:order[0],extra:order[1]}
                client.rpc("aura_mutate_playlist_items",{"p_playlist_id":ids[i],"p_item_id":extra,"p_action":"delete","p_payload":{}}).execute()
            anonymous=http.post(config["supabase_url"].rstrip("/")+"/rest/v1/rpc/aura_mutate_playlist_items",
                headers={"apikey":config["anon_key"]},json={"p_playlist_id":ids[0],"p_item_id":None,"p_action":"rest_patch","p_payload":{}})
            assert anonymous.status_code in (401,403,404)
            passed.append(step)
            step = "positive_pull_after_bootstrap"
            verify_positive_pull(api,device,ids,marker)
            passed.append(step)
            step = "sql_lock_and_atomicity"
            from verify_sec002_sql_controls import verify_controls
            verify_controls(config,api,clients[0][0],ids[0],items[0],track,marker)
            passed.append(step)
            step = "streaming_and_lot1_auth"
            assert http.get(urls+"/me/playlists").status_code == 401
            assert http.get(urls+"/me/playlists",headers={"Authorization":"Bearer SYNTHETIC_INVALID"}).status_code == 401
            assert http.get(urls+"/test").status_code == 404
            audio=synthetic_audio()
            for i in (0,1):
                audio_id="sec0023-audio-"+str(i)+marker
                path=urls+"/me/sync/files/"+audio_id
                header={"Authorization":"Bearer "+sessions[i].access_token}
                response=http.post(path,headers=header,files={"file":("synthetic.wav",audio,"audio/wav")})
                assert response.status_code == 201
                response=http.get(path,headers=header)
                assert response.status_code == 200 and response.content == audio
                response=http.get(path,params={"token":sessions[i].access_token},headers={"Range":"bytes=0-15"})
                assert response.status_code == 206 and response.content == audio[:16]
                assert response.headers["content-range"] == "bytes 0-15/"+str(len(audio))
                assert http.get(path).status_code == 401
                assert http.delete(path,headers=header).status_code == 200
            passed.append(step)
            step = "owner_crud_and_multiple_delete"
            for i in (0,1):
                extra="extra-"+str(i)+marker
                api(i,"POST","/me/playlists/"+ids[i]+"/tracks",{"id":extra,"track_id":track,"position":20})
                removed=api(i,"DELETE","/me/playlists/"+ids[i]+"/tracks/"+track)
                assert {v["id"] for v in removed} == {items[i],extra}
                assert api(i,"DELETE","/me/playlists/"+ids[i]+"/tracks/"+track) == []
                deleted=api(i,"DELETE","/me/playlists/"+ids[i])
                assert deleted["id"] == ids[i]
                assert not any(p["id"] == ids[i] for p in api(i,"GET","/me/playlists"))
            passed.append(step)
            step = "refresh"
            for i,(client,_) in enumerate(clients):
                sessions[i] = client.auth.refresh_session(sessions[i].refresh_token).session
                validate_live_session(sessions[i],config)
                api(i,"POST","/me/sync/bootstrap",{"device_id":device})
            passed.append(step)
            step = "real_expiration_401"
            old=sessions[0].access_token
            # Unverified exp schedules the wait only; the backend decides acceptance/rejection.
            expiry=jwt.decode(old,options={"verify_signature":False})["exp"]
            delay=expiry+2-time.time()
            assert 0 < delay <= config.get("max_expiry_wait_seconds",360)
            print(json.dumps({"status":"waiting_real_expiration","maximum_seconds":config.get("max_expiry_wait_seconds",360)}),flush=True)
            deadline=time.monotonic()+config.get("max_expiry_wait_seconds",360)
            while time.time() < expiry+2:
                assert time.monotonic() < deadline
                time.sleep(max(0,min(1,expiry+2-time.time())))
            assert http.get(urls+"/me/playlists",headers={"Authorization":"Bearer "+old}).status_code == 401
            for i,(client,_) in enumerate(clients):
                sessions[i]=client.auth.refresh_session(sessions[i].refresh_token).session
                validate_live_session(sessions[i],config)
                api(i,"GET","/me/playlists")
            passed.append(step)
            step = "logout_revokes_refresh"
            for i in (0,1):
                headers={"apikey":config["anon_key"],"Authorization":"Bearer "+sessions[i].access_token}
                response=http.post(config["supabase_url"].rstrip("/")+"/auth/v1/logout?scope=local",headers=headers)
                assert response.status_code == 204
                response=http.post(config["supabase_url"].rstrip("/")+"/auth/v1/token?grant_type=refresh_token",
                    headers={"apikey":config["anon_key"]},json={"refresh_token":sessions[i].refresh_token})
                assert response.status_code in (400,401,403)
            passed.append(step)
            print(json.dumps({"status":"passed","checks":passed,"accounts_created":False,
                              "data":"dedicated isolated fixture retained; no automatic project cleanup"}))
            return 0
    except Exception:
        print(json.dumps({"status":"failed","step":step,"passed_checks":passed,"details_redacted":True}))
        return 1
    finally:
        for client,transport in clients:
            try:
                client.auth.sign_out()
            except Exception:
                pass
            transport.close()


def main():
    if not __debug__:
        print(json.dumps({"status":"optimized_mode_refused","network_accessed":False,"writes":False}))
        return 2
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest",type=Path,required=True)
    parser.add_argument("--execute-isolated",action="store_true")
    args = parser.parse_args()
    try:
        config = json.loads(args.manifest.read_text(encoding="utf-8-sig"))
        validate_config(config)
    except Exception:
        print(json.dumps({"status":"invalid_manifest","details_redacted":True}))
        return 2
    if not args.execute_isolated:
        print(json.dumps({"status":"config_valid","network_accessed":False,"writes":False}))
        return 0
    try:
        validate_execution(config)
    except Exception:
        print(json.dumps({"status":"execution_preflight_failed","network_accessed":False,"writes":False,"details_redacted":True}))
        return 2
    return execute(config)


if __name__ == "__main__":
    raise SystemExit(main())
