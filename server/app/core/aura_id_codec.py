"""
Stateless AURA ID codec for SRV-002 public catalog endpoints.

Before SRV-004 persists a canonical online catalog in Postgres, the backend still
needs opaque IDs that Android can reuse across `/search`, `/artists/{id}` and
`/albums/{id}`.

This codec wraps provider references in backend-shaped opaque strings:
- art_<token>
- alb_<token>
- trk_<token>

The payload stays internal to the backend and is decoded only server-side.
Android never sees raw provider IDs directly.
"""

from __future__ import annotations

import base64
from dataclasses import dataclass


@dataclass(frozen=True)
class ProviderReference:
    entity_kind: str
    provider_name: str
    provider_id: str


_VERSION = "v1"
_PREFIX_BY_KIND = {
    "artist": "art",
    "album": "alb",
    "track": "trk",
}
_KIND_BY_PREFIX = {value: key for key, value in _PREFIX_BY_KIND.items()}


def build_aura_id(entity_kind: str, provider_name: str, provider_id: str) -> str:
    """Build an opaque AURA transport ID from a provider reference."""
    prefix = _PREFIX_BY_KIND.get(entity_kind)
    if prefix is None:
        raise ValueError(f"Unsupported entity kind: {entity_kind}")

    normalized_provider_name = provider_name.strip().lower()
    normalized_provider_id = provider_id.strip()
    if not normalized_provider_name or not normalized_provider_id:
        raise ValueError("Provider reference must be non-empty")

    payload = f"{_VERSION}:{entity_kind}:{normalized_provider_name}:{normalized_provider_id}"
    token = base64.urlsafe_b64encode(payload.encode("utf-8")).decode("ascii").rstrip("=")
    return f"{prefix}_{token}"


def parse_aura_id(aura_id: str, expected_kind: str | None = None) -> ProviderReference:
    """Resolve an opaque AURA transport ID back to its provider reference."""
    prefix, separator, token = aura_id.partition("_")
    if separator != "_" or prefix not in _KIND_BY_PREFIX:
        raise ValueError("Unsupported AURA ID format")

    entity_kind = _KIND_BY_PREFIX[prefix]
    if expected_kind is not None and entity_kind != expected_kind:
        raise ValueError(f"Expected {expected_kind} ID, got {entity_kind}")

    padding = "=" * (-len(token) % 4)
    try:
        payload = base64.urlsafe_b64decode(f"{token}{padding}".encode("ascii")).decode("utf-8")
    except Exception as exc:
        raise ValueError("Malformed AURA ID payload") from exc

    version, decoded_kind, provider_name, provider_id = payload.split(":", maxsplit=3)
    if version != _VERSION or decoded_kind != entity_kind or not provider_name or not provider_id:
        raise ValueError("Unsupported AURA ID payload")

    return ProviderReference(
        entity_kind=entity_kind,
        provider_name=provider_name,
        provider_id=provider_id,
    )


def get_track_id_aliases(track_id: str) -> list[str]:
    """
    Retourne la liste ordonnée des alias équivalents pour un identifiant de piste.
    Permet de réconcilier les identifiants opaques AURA (trk_...), les identifiants avec préfixe (deezer:..., ytm:...)
    et les identifiants numériques bruts (ex: 3123456).
    """
    raw = track_id.strip()
    if not raw:
        return []

    candidates: list[str] = [raw]

    # 1. Identifiant opaque AURA (trk_...)
    if raw.startswith("trk_"):
        try:
            ref = parse_aura_id(raw, expected_kind="track")
            p_name = ref.provider_name.lower()
            p_id = ref.provider_id
            candidates.append(f"{p_name}:{p_id}")
            candidates.append(p_id)
            if p_name == "deezer":
                candidates.append(f"deezer:{p_id}")
            elif p_name in ("youtube", "ytmusic", "ytm"):
                candidates.append(f"ytm:{p_id}")
                candidates.append(f"youtube:{p_id}")
        except Exception:
            pass

    # 2. Préfixe deezer:
    elif raw.startswith("deezer:"):
        num_id = raw.split(":", 1)[1].strip()
        candidates.append(num_id)
        try:
            candidates.append(build_aura_id("track", "deezer", num_id))
        except Exception:
            pass

    # 3. ID numérique pur (Deezer standard)
    elif raw.isdigit():
        candidates.append(f"deezer:{raw}")
        try:
            candidates.append(build_aura_id("track", "deezer", raw))
        except Exception:
            pass

    # 4. Préfixe ytm: ou youtube:
    elif raw.startswith("ytm:") or raw.startswith("youtube:"):
        yt_id = raw.split(":", 1)[1].strip()
        candidates.append(f"ytm:{yt_id}")
        candidates.append(f"youtube:{yt_id}")
        try:
            candidates.append(build_aura_id("track", "youtube", yt_id))
        except Exception:
            pass

    # 5. Préfixe spotify:
    elif raw.startswith("spotify:"):
        sp_id = raw.split(":", 1)[1].strip()
        try:
            candidates.append(build_aura_id("track", "spotify", sp_id))
        except Exception:
            pass

    # Déduplication avec préservation de l'ordre
    seen: set[str] = set()
    result: list[str] = []
    for c in candidates:
        if c and c not in seen:
            seen.add(c)
            result.append(c)

    return result

