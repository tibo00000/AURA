"""Exercise the real authentication dependency using only synthetic users and keys."""

import json
import hashlib
import hmac
import time
from unittest.mock import patch
from types import SimpleNamespace

import jwt
import pytest
from cryptography.hazmat.primitives.asymmetric import ec, rsa
from fastapi import Depends, FastAPI, HTTPException
from fastapi.testclient import TestClient

from app.config import Settings
from app.core.auth import _decode_jwt_payload, get_current_user


ISSUER = "https://auth.example.test/auth/v1"
USER_A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
USER_B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
FORMER_OWNER = "12345678-1234-1234-1234-1234567890ab"
SECRET = "synthetic-test-signing-secret-for-fixtures-with-at-least-64-bytes-only"


def claims(**overrides):
    return {
        "sub": USER_A, "iss": ISSUER, "aud": "authenticated",
        "role": "authenticated", "exp": int(time.time()) + 300,
        **overrides,
    }


@pytest.fixture
def settings():
    config = Settings(
        _env_file=None, supabase_url="https://auth.example.test",
        supabase_jwt_secret=SECRET,
    )
    with patch("app.core.auth.get_settings", return_value=config):
        yield config


@pytest.fixture
def client(settings):
    app = FastAPI()

    @app.get("/private")
    async def private(user=Depends(get_current_user)):
        return {"id": user.id, "token": user.token}

    with TestClient(app) as test_client:
        yield test_client


def signed(payload=None, key=SECRET, algorithm="HS256", headers=None):
    # JWS signing intentionally permits malformed claim types in negative fixtures.
    return jwt.api_jws.encode(
        json.dumps(claims() if payload is None else payload).encode(),
        key, algorithm=algorithm, headers=headers,
    )


def forged_hmac_with_public_key(payload, public_key_bytes, kid):
    header = jwt.utils.base64url_encode(json.dumps({"alg": "HS256", "kid": kid}).encode())
    body = jwt.utils.base64url_encode(json.dumps(payload).encode())
    message = header + b"." + body
    signature = jwt.utils.base64url_encode(hmac.new(public_key_bytes, message, hashlib.sha256).digest())
    return (message + b"." + signature).decode()


def assert_rejected(client, token):
    response = client.get("/private", headers={"Authorization": f"Bearer {token}"})
    assert response.status_code == 401
    assert response.headers["www-authenticate"] == "Bearer"
    assert set(response.json()) == {"detail"}  # Handler never ran.


@pytest.mark.parametrize("user_id", [USER_A, USER_B, FORMER_OWNER])
@pytest.mark.parametrize("transport", ["header", "query", "prefixed_query"])
def test_signed_identity_and_streaming_transports(client, user_id, transport):
    token = signed(claims(sub=user_id.upper()))
    kwargs = (
        {"headers": {"Authorization": f"Bearer {token}"}} if transport == "header"
        else {"params": {"token": f"Bearer {token}" if transport == "prefixed_query" else token}}
    )
    response = client.get("/private", **kwargs)
    assert response.status_code == 200
    assert response.json() == {"id": user_id, "token": token}


@pytest.mark.parametrize("token", [FORMER_OWNER, FORMER_OWNER.upper(), "not.a.jwt", ""])
def test_fixed_owner_and_malformed_tokens_rejected_everywhere(client, token):
    assert_rejected(client, token)
    for value in (token, f"Bearer {token}"):
        assert client.get("/private", params={"token": value}).status_code == 401


@pytest.mark.parametrize("header", ["Basic ignored", "Bearer invalid", "Bearer", "Bearer a b", ""])
def test_invalid_header_never_falls_back_to_query(client, header):
    assert client.get(
        "/private", headers={"Authorization": header}, params={"token": signed()}
    ).status_code == 401


def test_missing_credentials(client):
    assert client.get("/private").status_code == 401


@pytest.mark.parametrize("field", ["sub", "iss", "aud", "exp", "role"])
def test_required_claims(client, field):
    payload = claims()
    del payload[field]
    assert_rejected(client, signed(payload))


@pytest.mark.parametrize("overrides", [
    {"exp": int(time.time()) - 60}, {"exp": "4102444800"}, {"exp": True},
    {"exp": float("inf")}, {"exp": float("nan")},
    {"aud": "anon"}, {"aud": None}, {"iss": "https://attacker.test/auth/v1"},
    {"iss": None}, {"sub": None}, {"sub": 12}, {"sub": "not-a-uuid"},
    {"role": "service_role"}, {"role": "anon"}, {"nbf": int(time.time()) + 300},
])
def test_invalid_claims(client, overrides):
    assert_rejected(client, signed(claims(**overrides)))


@pytest.mark.parametrize("algorithm,key", [
    ("HS256", "different-synthetic-signing-secret-32-bytes"),
    ("HS384", SECRET), ("none", None),
])
def test_forged_unsigned_and_disallowed_algorithm(client, algorithm, key):
    assert_rejected(client, signed(key=key, algorithm=algorithm))


def test_tampered_other_account_claim_is_rejected(client):
    token = signed()
    header, payload, signature = token.split(".")
    data = json.loads(jwt.utils.base64url_decode(payload))
    data["sub"] = USER_B
    changed = jwt.utils.base64url_encode(json.dumps(data).encode()).decode()
    assert_rejected(client, f"{header}.{changed}.{signature}")


@pytest.mark.parametrize("url", ["", "http://auth.example.test", "https://a:b@auth.example.test", "https://auth.example.test?q=x"])
def test_untrusted_or_missing_configuration(client, settings, url):
    settings.supabase_url = url
    assert_rejected(client, signed())


def test_no_hs256_secret_does_not_decode_unsigned_claims(client, settings):
    settings.supabase_jwt_secret = ""
    with patch("urllib.request.OpenerDirector.open", side_effect=AssertionError("HS256 must not fetch keys")):
        assert_rejected(client, signed())


@pytest.fixture(params=["ES256", "RS256"])
def signing_key(request):
    algorithm = request.param
    key = ec.generate_private_key(ec.SECP256R1()) if algorithm == "ES256" else rsa.generate_private_key(public_exponent=65537, key_size=2048)
    public_jwk = jwt.algorithms.get_default_algorithms()[algorithm].to_jwk(key.public_key(), as_dict=True)
    public_jwk.update(kid="synthetic-current-key", alg=algorithm, use="sig")
    return algorithm, key, public_jwk


def mock_jwks(public_keys):
    """Replace only network I/O; exercise the real PyJWKClient and cryptography."""
    from io import BytesIO
    return patch("urllib.request.OpenerDirector.open", return_value=BytesIO(json.dumps({"keys": public_keys}).encode()))


def test_jwks_valid_token_cache_and_trusted_destination(client, settings, signing_key):
    from app.core.auth import _jwks_client
    _jwks_client.cache_clear()
    settings.supabase_jwt_secret = ""
    algorithm, key, public_jwk = signing_key
    token = signed(key=key, algorithm=algorithm, headers={"kid": public_jwk["kid"], "jku": "https://attacker.test/keys"})
    with mock_jwks([public_jwk]) as fetch:
        for _ in range(2):
            assert client.get("/private", params={"token": token}).status_code == 200
        assert fetch.call_count == 1
        assert fetch.call_args.args[0].full_url == ISSUER + "/.well-known/jwks.json"
    _jwks_client.cache_clear()


def test_jwks_wrong_key_unknown_kid_missing_kid_and_alg_confusion(client, settings, signing_key):
    from app.core.auth import _jwks_client
    settings.supabase_jwt_secret = ""
    algorithm, key, public_jwk = signing_key
    other_key = ec.generate_private_key(ec.SECP256R1()) if algorithm == "ES256" else rsa.generate_private_key(public_exponent=65537, key_size=2048)
    for token in [
        signed(key=other_key, algorithm=algorithm, headers={"kid": public_jwk["kid"]}),
        signed(key=key, algorithm=algorithm, headers={"kid": "unknown"}),
        signed(key=key, algorithm=algorithm),
        # A public key's DER bytes must never become an HMAC secret.
        forged_hmac_with_public_key(claims(), json.dumps(public_jwk).encode(), public_jwk["kid"]),
    ]:
        _jwks_client.cache_clear()
        # Fresh streams for PyJWKClient's refresh on unknown kid.
        from io import BytesIO
        with patch("urllib.request.OpenerDirector.open", side_effect=lambda *a, **k: BytesIO(json.dumps({"keys": [public_jwk]}).encode())):
            assert_rejected(client, token)
    _jwks_client.cache_clear()


def test_jwks_rotation_refresh(client, settings, signing_key):
    from app.core.auth import _jwks_client
    from io import BytesIO
    _jwks_client.cache_clear()
    settings.supabase_jwt_secret = ""
    algorithm, key, public_jwk = signing_key
    previous = dict(public_jwk, kid="previous-key")
    responses = [BytesIO(json.dumps({"keys": [previous]}).encode()), BytesIO(json.dumps({"keys": [public_jwk]}).encode())]
    token = signed(key=key, algorithm=algorithm, headers={"kid": public_jwk["kid"]})
    with patch("urllib.request.OpenerDirector.open", side_effect=responses) as fetch:
        with patch("jwt.jwks_client.time", SimpleNamespace(monotonic=lambda: 0)):
            previous_token = signed(key=key, algorithm=algorithm, headers={"kid": "previous-key"})
            assert client.get("/private", params={"token": previous_token}).status_code == 200
            assert_rejected(client, token)  # Unknown kid cannot force unlimited refreshes.
            assert fetch.call_count == 1
        with patch("jwt.jwks_client.time", SimpleNamespace(monotonic=lambda: 31)):
            assert client.get("/private", params={"token": token}).status_code == 200
        assert fetch.call_count == 2
    _jwks_client.cache_clear()


@pytest.mark.parametrize("failure", ["offline", "empty", "malformed"])
def test_unavailable_or_invalid_jwks_fail_closed(client, settings, signing_key, failure):
    from app.core.auth import _jwks_client
    from io import BytesIO
    from urllib.error import URLError
    _jwks_client.cache_clear()
    settings.supabase_jwt_secret = ""
    algorithm, key, public_jwk = signing_key
    token = signed(key=key, algorithm=algorithm, headers={"kid": public_jwk["kid"]})
    kwargs = {"side_effect": URLError("offline")} if failure == "offline" else {"return_value": BytesIO(b'{"keys": []}' if failure == "empty" else b'broken json')}
    with patch("urllib.request.OpenerDirector.open", **kwargs):
        assert_rejected(client, token)
    _jwks_client.cache_clear()


def test_production_routes_exclude_test_downloader():
    from app.main import app
    from app.api.router import api_router
    assert not any(getattr(route, "path", "").startswith("/test") for route in api_router.routes)
    assert not any(path.startswith("/test") for path in app.openapi()["paths"])
    with TestClient(app) as client:
        for path in ["/test", "/test/download", "/test/downloads", "/test/downloads/id", "/test/downloads/id/file"]:
            for method in ("GET", "POST", "DELETE", "PUT", "PATCH", "HEAD", "OPTIONS"):
                assert client.request(method, path, json={"query": "synthetic"}).status_code == 404
        assert client.get("/health").status_code == 200
