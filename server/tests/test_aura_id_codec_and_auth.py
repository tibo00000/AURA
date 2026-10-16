"""
Unit tests for AURA ID codec (aura_id_codec.py) and JWT/Bearer authentication (auth.py).
"""

import asyncio
import base64
import hashlib
import hmac
import json
import sys
import time
import unittest
from unittest.mock import MagicMock, patch

for mod in ("yt_dlp", "ytmusicapi"):
    if mod not in sys.modules:
        sys.modules[mod] = MagicMock()

from fastapi import HTTPException
from app.core.auth import AuthenticatedUser, _decode_jwt_payload, get_current_user
from app.core.aura_id_codec import (
    build_aura_id,
    get_track_id_aliases,
    parse_aura_id,
)


def _make_hs256_jwt(payload: dict, secret: str) -> str:
    header = {"alg": "HS256", "typ": "JWT"}
    h_b64 = base64.urlsafe_b64encode(json.dumps(header).encode()).decode().rstrip("=")
    p_b64 = base64.urlsafe_b64encode(json.dumps(payload).encode()).decode().rstrip("=")
    signing_input = f"{h_b64}.{p_b64}".encode("ascii")
    sig = hmac.new(secret.encode("utf-8"), signing_input, hashlib.sha256).digest()
    s_b64 = base64.urlsafe_b64encode(sig).decode().rstrip("=")
    return f"{h_b64}.{p_b64}.{s_b64}"


class TestAuraIdCodec(unittest.TestCase):

    def test_build_and_parse_roundtrip_all_kinds(self):
        for kind, prefix in (("artist", "art"), ("album", "alb"), ("track", "trk")):
            aura_id = build_aura_id(kind, "deezer", "3135556")
            self.assertTrue(aura_id.startswith(f"{prefix}_"))
            ref = parse_aura_id(aura_id, expected_kind=kind)
            self.assertEqual(ref.entity_kind, kind)
            self.assertEqual(ref.provider_name, "deezer")
            self.assertEqual(ref.provider_id, "3135556")

    def test_parse_rejects_invalid_format_and_kind_mismatch(self):
        art_id = build_aura_id("artist", "deezer", "27")
        with self.assertRaises(ValueError):
            parse_aura_id(art_id, expected_kind="track")

        with self.assertRaises(ValueError):
            parse_aura_id("invalid_prefix_abc", expected_kind="track")

        with self.assertRaises(ValueError):
            parse_aura_id("trk_!!!not_base64!!!", expected_kind="track")

        with self.assertRaises(ValueError):
            build_aura_id("unknown_kind", "deezer", "123")

    def test_get_track_id_aliases_expands_providers_and_isolates_local(self):
        # Spotify track ID
        sp_aura = build_aura_id("track", "spotify", "4cOdK2wGLETKBW3PvgPWqT")
        sp_aliases = get_track_id_aliases(sp_aura)
        self.assertIn(sp_aura, sp_aliases)
        self.assertIn("spotify:4cOdK2wGLETKBW3PvgPWqT", sp_aliases)

        # Deezer track ID
        dz_aura = build_aura_id("track", "deezer", "998877")
        dz_aliases = get_track_id_aliases(dz_aura)
        self.assertIn(dz_aura, dz_aliases)
        self.assertIn("deezer:998877", dz_aliases)
        self.assertIn("998877", dz_aliases)

        # YTM track ID
        yt_aura = build_aura_id("track", "ytm", "dQw4w9WgXcQ")
        yt_aliases = get_track_id_aliases(yt_aura)
        self.assertIn(yt_aura, yt_aliases)
        self.assertIn("ytm:dQw4w9WgXcQ", yt_aliases)
        self.assertIn("youtube:dQw4w9WgXcQ", yt_aliases)

        # Local non-aura ID remains isolated to itself
        self.assertEqual(get_track_id_aliases("local_media_42"), ["local_media_42"])


class TestAuthModule(unittest.TestCase):

    def test_decode_jwt_fallback_and_malformed_rejection(self):
        mock_settings = MagicMock()
        mock_settings.supabase_jwt_secret = None

        with patch("app.core.auth.get_settings", return_value=mock_settings):
            valid_token = _make_hs256_jwt(
                {"sub": "11111111-2222-3333-4444-555555555555", "aud": "authenticated", "exp": int(time.time()) + 3600},
                "any_secret",
            )
            payload = _decode_jwt_payload(valid_token)
            self.assertEqual(payload["sub"], "11111111-2222-3333-4444-555555555555")

            with self.assertRaises(HTTPException) as ctx:
                _decode_jwt_payload("not.a.valid.jwt")
            self.assertEqual(ctx.exception.status_code, 401)

    def test_get_current_user_extracts_bearer_query_and_rejects_missing_or_invalid(self):
        mock_settings = MagicMock()
        mock_settings.supabase_jwt_secret = None
        user_uuid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        jwt_token = _make_hs256_jwt(
            {"sub": user_uuid, "aud": "authenticated", "exp": int(time.time()) + 3600},
            "test_secret",
        )

        with patch("app.core.auth.get_settings", return_value=mock_settings):
            # 1. Valid Bearer header
            user_header = asyncio.run(get_current_user(authorization=f"Bearer {jwt_token}", token=None))
            self.assertIsInstance(user_header, AuthenticatedUser)
            self.assertEqual(user_header.id, user_uuid)

            # 2. Valid query parameter fallback (used for media streaming)
            user_query = asyncio.run(get_current_user(authorization=None, token=jwt_token))
            self.assertEqual(user_query.id, user_uuid)

            # 3. Missing credentials -> 401
            with self.assertRaises(HTTPException) as ctx_missing:
                asyncio.run(get_current_user(authorization=None, token=None))
            self.assertEqual(ctx_missing.exception.status_code, 401)

            # 4. Invalid Authorization header scheme -> 401
            with self.assertRaises(HTTPException) as ctx_scheme:
                asyncio.run(get_current_user(authorization=f"Basic {jwt_token}", token=None))
            self.assertEqual(ctx_scheme.exception.status_code, 401)

            # 5. Malformed token -> 401
            with self.assertRaises(HTTPException) as ctx_invalid:
                asyncio.run(get_current_user(authorization="Bearer invalid_token", token=None))
            self.assertEqual(ctx_invalid.exception.status_code, 401)


if __name__ == "__main__":
    unittest.main()
