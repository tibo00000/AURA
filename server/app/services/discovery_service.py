"""
Discovery Engine — Multi-strategy recommendation service.

Generates personalized music discovery items using 4 parallel strategies,
attributes each recommendation to its source method, triggers pre-downloads,
and adapts strategy weights based on user feedback.

Strategies:
    - artist_radar:  Top artists → Deezer /related → recent albums
    - genre_drift:   Genre fingerprint → Deezer /editorial releases
    - artist_radio:  Top artists → Deezer /artist radio → filter known
    - wildcard:      Deezer charts + editorial picks → filter known artists

Correctifs intégrés (review):
    - Cold start detection (< 3 artists → 100% wildcard)
    - Inter-batch deduplication (30-day window, except unseen items)
    - Scoring normalized by conversion rate (not absolute sum)
    - Mutex per user_id on generate_batch
    - Quota enforcement on pre-downloads (max 10/batch, 30 total)
    - Soft-delete for expired items (preserves feedback history)
    - is_expired set alongside file_purged
"""

import asyncio
import logging
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from typing import Dict, List, Optional, Set

from app.config import get_settings
from app.core.id_generator import generate_id
from app.db.supabase import supabase
from app.providers.deezer.client import DeezerClient
from app.providers.deezer.exceptions import DeezerError, DeezerNotFound

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Data classes
# ---------------------------------------------------------------------------


@dataclass
class ArtistAffinity:
    """An artist in the user's taste profile with computed affinity score."""
    aura_artist_id: str
    artist_name: str
    deezer_artist_id: Optional[int] = None
    affinity_score: float = 0.0


@dataclass
class UserProfile:
    """Aggregated user taste profile built from likes, playlists, history."""
    user_id: str
    top_artists: List[ArtistAffinity] = field(default_factory=list)
    genre_fingerprint: Dict[int, float] = field(default_factory=dict)  # {deezer_genre_id: weight}
    known_deezer_track_ids: Set[int] = field(default_factory=set)
    is_cold_start: bool = False


@dataclass
class DiscoveryCandidate:
    """A candidate recommendation before final scoring."""
    deezer_track_id: int
    deezer_album_id: Optional[int]
    deezer_artist_id: int
    track_title: str
    artist_name: str
    album_title: Optional[str]
    cover_url: Optional[str]
    duration_ms: Optional[int]
    release_date: Optional[str]
    record_type: Optional[str]
    preview_url: Optional[str]
    source_strategy: str
    source_artist_name: Optional[str] = None
    source_deezer_artist_id: Optional[int] = None
    reason_text: Optional[str] = None
    score: float = 0.0


# ---------------------------------------------------------------------------
# DiscoveryService
# ---------------------------------------------------------------------------


class DiscoveryService:
    """
    Multi-strategy recommendation engine.

    Generates discovery items using 4 strategies simultaneously,
    attributes each to its source, and triggers pre-downloads.
    """

    # Maximum pre-downloads per batch
    MAX_PREDOWNLOADS_PER_BATCH = 10
    # Maximum total non-expired pre-downloaded tracks per user
    MAX_PREDOWNLOADED_TOTAL = 30
    # Staleness threshold: don't regenerate if latest batch is newer than this
    STALENESS_HOURS = 24
    # How far back to look for inter-batch deduplication
    DEDUP_DAYS = 30
    # Minimum artists in profile before full pipeline activates
    COLD_START_THRESHOLD = 3

    def __init__(self, download_service=None):
        settings = get_settings()
        self.deezer = DeezerClient(settings.deezer_api_base_url)
        self.download_service = download_service

        # Mutex per user_id — same pattern as DownloadService._track_locks.
        # NOTE: Process-local only. Confirmed single-worker Uvicorn deployment.
        self._generation_locks: Dict[str, asyncio.Lock] = {}
        self._generation_locks_guard = asyncio.Lock()
        self._track_artist_cache: Dict[str, Tuple[str, str]] = {}

    async def _get_generation_lock(self, user_id: str) -> asyncio.Lock:
        """Get or create a per-user asyncio lock for generate_batch."""
        async with self._generation_locks_guard:
            if user_id not in self._generation_locks:
                self._generation_locks[user_id] = asyncio.Lock()
            return self._generation_locks[user_id]

    # ===================================================================
    # User Profile
    # ===================================================================

    async def build_user_profile(self, user_id: str) -> UserProfile:
        """
        Aggregate signals from Supabase to build a taste profile.

        Sources: likes, playlists, history, downloads.
        Cold start: if < COLD_START_THRESHOLD distinct artists, only wildcard runs.
        """
        scores: Dict[str, float] = defaultdict(float)
        artist_names: Dict[str, str] = {}
        now = datetime.now(timezone.utc)

        # Signal 1: Likes (strongest explicit signal)
        try:
            likes_resp = supabase.table("likes") \
                .select("track_id, liked_at") \
                .eq("user_id", user_id) \
                .execute()
            for like in (likes_resp.data or []):
                track_id = like["track_id"]
                artist_info = await self._resolve_artist_from_track_id(track_id, user_id)
                if artist_info:
                    aid, aname = artist_info
                    artist_names[aid] = aname
                    scores[aid] += 5.0
                    # Temporal decay: recent likes worth more
                    if like.get("liked_at"):
                        try:
                            liked_dt = datetime.fromisoformat(like["liked_at"].replace("Z", "+00:00"))
                            age_days = (now - liked_dt).days
                            scores[aid] += max(0.0, 3.0 * (1 - age_days / 365))
                        except (ValueError, TypeError):
                            pass
        except Exception as e:
            logger.warning("Failed to load likes for profile: %s", e)

        # Signal 2: Playlists (curated groupings)
        try:
            playlists_resp = supabase.table("playlists") \
                .select("id, is_pinned") \
                .eq("user_id", user_id) \
                .execute()
            for pl in (playlists_resp.data or []):
                weight = 4.0 if pl.get("is_pinned") else 2.0
                items_resp = supabase.table("playlist_items") \
                    .select("track_id") \
                    .eq("playlist_id", pl["id"]) \
                    .execute()
                artist_counts: Dict[str, int] = defaultdict(int)
                for item in (items_resp.data or []):
                    artist_info = await self._resolve_artist_from_track_id(item["track_id"], user_id)
                    if artist_info:
                        aid, aname = artist_info
                        artist_names[aid] = aname
                        artist_counts[aid] += 1
                for aid, count in artist_counts.items():
                    scores[aid] += weight * min(count, 5)
        except Exception as e:
            logger.warning("Failed to load playlists for profile: %s", e)

        # Signal 3: Listening history (last 90 days, non-skipped)
        try:
            cutoff_90d = (now - timedelta(days=90)).isoformat()
            history_resp = supabase.table("history_items") \
                .select("track_id, completion_percent, was_skipped") \
                .eq("user_id", user_id) \
                .gte("played_at", cutoff_90d) \
                .execute()
            for item in (history_resp.data or []):
                if item.get("was_skipped"):
                    continue
                completion = item.get("completion_percent") or 0
                if completion > 0.7:
                    artist_info = await self._resolve_artist_from_track_id(item["track_id"], user_id)
                    if artist_info:
                        aid, aname = artist_info
                        artist_names[aid] = aname
                        scores[aid] += 1.5
        except Exception as e:
            logger.warning("Failed to load history for profile: %s", e)

        # Build sorted artist list
        top_artists = []
        for aid, score in sorted(scores.items(), key=lambda x: x[1], reverse=True)[:20]:
            top_artists.append(ArtistAffinity(
                aura_artist_id=aid,
                artist_name=artist_names.get(aid, "Unknown"),
                affinity_score=score,
            ))

        # Resolve Deezer IDs
        await self._resolve_deezer_ids_for_artists(top_artists)

        # Build genre fingerprint from top artists' Deezer albums
        genre_fingerprint = await self._build_genre_fingerprint(top_artists[:10])

        profile = UserProfile(
            user_id=user_id,
            top_artists=top_artists,
            genre_fingerprint=genre_fingerprint,
        )

        # Cold start detection
        artists_with_deezer = [a for a in top_artists if a.deezer_artist_id]
        if len(artists_with_deezer) < self.COLD_START_THRESHOLD:
            profile.is_cold_start = True
            logger.info("Cold start detected for user %s (%d artists)", user_id, len(artists_with_deezer))

        return profile

    async def _resolve_artist_from_track_id(self, track_id: str, user_id: Optional[str] = None) -> Optional[tuple]:
        """
        Extract (artist_id, artist_name) from a track_id.
        Handles:
        1. In-memory cache for speed
        2. AURA opaque transport IDs (e.g. trk_djE... => Deezer track ID)
        3. Plain Deezer track IDs (trk_deezer_12345, deezer:12345)
        4. User synced files metadata (.json on VPS) for track:local:* and other tracks
        5. Global cache metadata on VPS
        """
        if track_id in self._track_artist_cache:
            return self._track_artist_cache[track_id]

        # 1. Try decoding AURA transport codec (e.g. trk_djE6dHJhY2s6ZGVlemVyOjUxODQ1ODA5Mg)
        if track_id.startswith("trk_"):
            try:
                from app.core.aura_id_codec import parse_aura_id
                ref = parse_aura_id(track_id)
                if ref.provider_name == "deezer" and ref.provider_id:
                    track_data = await self.deezer.get_track(ref.provider_id)
                    artist = track_data.get("artist", {})
                    if artist.get("name"):
                        res = (f"deezer:{artist['id']}", artist["name"])
                        self._track_artist_cache[track_id] = res
                        return res
            except Exception:
                pass

        # 2. Plain Deezer format (trk_deezer_12345, deezer:12345)
        if "deezer" in track_id.lower():
            try:
                for p in track_id.replace(":", "_").split("_"):
                    if p.isdigit():
                        track_data = await self.deezer.get_track(p)
                        artist = track_data.get("artist", {})
                        if artist.get("name"):
                            res = (f"deezer:{artist['id']}", artist["name"])
                            self._track_artist_cache[track_id] = res
                            return res
            except Exception:
                pass

        # 3. Look up metadata in user's sync directory on VPS (for track:local:* etc.)
        try:
            from app.api.routes.sync_files import _paths, _read_metadata
            from app.services.download_service import _find_globally_cached_track
            from app.core.aura_id_codec import get_track_id_aliases

            candidates = get_track_id_aliases(track_id)
            for cand in candidates:
                if user_id:
                    _, meta_path = _paths(user_id, cand)
                    meta = _read_metadata(meta_path)
                    if meta and meta.get("artist_name"):
                        aname = meta["artist_name"].strip()
                        aid = meta.get("artist_id") or f"artist:{aname.lower()}"
                        res = (aid, aname)
                        self._track_artist_cache[track_id] = res
                        return res

                # Fallback to _global_cache
                cached = _find_globally_cached_track(cand)
                if cached and cached[1] and cached[1].get("artist_name"):
                    aname = cached[1]["artist_name"].strip()
                    aid = cached[1].get("artist_id") or f"artist:{aname.lower()}"
                    res = (aid, aname)
                    self._track_artist_cache[track_id] = res
                    return res
        except Exception:
            pass

        # 4. Fallback for cloud tracks (e.g. trk_cloud_sdm_bolideallemand)
        if track_id.startswith("trk_cloud_"):
            parts = track_id.split("_")
            if len(parts) >= 3 and parts[2]:
                artist_hint = parts[2].title()
                res = (f"cloud:{parts[2].lower()}", artist_hint)
                self._track_artist_cache[track_id] = res
                return res

        return None

    async def _resolve_deezer_ids_for_artists(self, artists: List[ArtistAffinity]) -> None:
        """
        Resolve AURA artist IDs to Deezer artist IDs.
        Uses cached mappings first, then falls back to Deezer search.
        """
        for artist in artists:
            if artist.deezer_artist_id:
                continue

            # Check cache
            try:
                cached = supabase.table("artist_deezer_mapping") \
                    .select("deezer_artist_id") \
                    .eq("aura_artist_id", artist.aura_artist_id) \
                    .execute()
                if cached.data:
                    artist.deezer_artist_id = cached.data[0]["deezer_artist_id"]
                    continue
            except Exception:
                pass

            # If the artist_id is already a deezer reference (deezer:12345)
            if artist.aura_artist_id.startswith("deezer:"):
                try:
                    deezer_id = int(artist.aura_artist_id.split(":")[1])
                    artist.deezer_artist_id = deezer_id
                    await self._cache_artist_mapping(artist)
                    continue
                except (ValueError, IndexError):
                    pass

            # Search Deezer by artist name
            if artist.artist_name and artist.artist_name != "Unknown":
                try:
                    results = await self.deezer.search(
                        query=f'artist:"{artist.artist_name}"',
                        resource_type="artist",
                        limit=5,
                    )
                    candidates = results.get("data", [])
                    if candidates:
                        # Pick the one with most fans (avoid homonyms)
                        best = max(candidates, key=lambda x: x.get("nb_fan", 0))
                        artist.deezer_artist_id = best["id"]
                        await self._cache_artist_mapping(artist)
                except DeezerError as e:
                    logger.debug("Failed to resolve Deezer ID for %s: %s", artist.artist_name, e)

    async def _cache_artist_mapping(self, artist: ArtistAffinity) -> None:
        """Cache an AURA→Deezer artist mapping."""
        try:
            supabase.table("artist_deezer_mapping").upsert({
                "aura_artist_id": artist.aura_artist_id,
                "deezer_artist_id": artist.deezer_artist_id,
                "artist_name": artist.artist_name,
                "resolved_at": datetime.now(timezone.utc).isoformat(),
                "confidence": 1.0,
            }).execute()
        except Exception as e:
            logger.debug("Failed to cache artist mapping: %s", e)

    async def _build_genre_fingerprint(self, artists: List[ArtistAffinity]) -> Dict[int, float]:
        """
        Build a genre distribution from the user's top artists.
        Uses Deezer album genre_id as the signal.
        """
        genre_scores: Dict[int, float] = defaultdict(float)
        for artist in artists:
            if not artist.deezer_artist_id:
                continue
            try:
                albums = await self.deezer.get_artist_albums(str(artist.deezer_artist_id), limit=5)
                for album in albums:
                    genre_id = album.get("genre_id")
                    if genre_id and genre_id > 0:
                        genre_scores[genre_id] += artist.affinity_score
            except DeezerError:
                continue

        # Normalize to probability distribution
        total = sum(genre_scores.values())
        if total > 0:
            return {g: s / total for g, s in genre_scores.items()}
        return {}

    # ===================================================================
    # The 4 Strategies
    # ===================================================================

    async def _strategy_artist_radar(
        self, profile: UserProfile, excluded_ids: Set[int]
    ) -> List[DiscoveryCandidate]:
        """
        Artist Radar: Top artists → /related → recent albums → tracks.

        Discovers new releases from artists similar to the user's favorites.
        source_strategy = 'artist_radar'
        """
        if profile.is_cold_start:
            return []

        candidates = []
        cutoff = (datetime.now(timezone.utc) - timedelta(weeks=6)).strftime("%Y-%m-%d")

        for artist in profile.top_artists[:10]:
            if not artist.deezer_artist_id:
                continue

            # Get related artists (with graph cache)
            related = await self._get_related_artists_cached(artist.deezer_artist_id)

            # Check recent releases from related artists
            for rel in related[:8]:
                rel_id = rel.get("id") or rel.get("related_deezer_artist_id")
                rel_name = rel.get("name") or rel.get("related_artist_name", "Unknown")
                if not rel_id:
                    continue

                try:
                    albums = await self.deezer.get_artist_albums(str(rel_id), limit=5)
                    for album in albums:
                        release = album.get("release_date", "")
                        if release and release >= cutoff:
                            # Get tracks from this album
                            try:
                                tracks = await self.deezer.get_album_tracks(str(album["id"]), limit=5)
                                for track in tracks[:2]:  # Max 2 tracks per album
                                    tid = track.get("id")
                                    if tid and tid not in excluded_ids:
                                        candidates.append(DiscoveryCandidate(
                                            deezer_track_id=tid,
                                            deezer_album_id=album.get("id"),
                                            deezer_artist_id=rel_id,
                                            track_title=track.get("title", "Unknown"),
                                            artist_name=rel_name,
                                            album_title=album.get("title"),
                                            cover_url=album.get("cover_medium") or album.get("cover_big"),
                                            duration_ms=(track.get("duration") or 0) * 1000,
                                            release_date=release,
                                            record_type=album.get("record_type"),
                                            preview_url=track.get("preview"),
                                            source_strategy="artist_radar",
                                            source_artist_name=artist.artist_name,
                                            source_deezer_artist_id=artist.deezer_artist_id,
                                            reason_text=f"Fans de {artist.artist_name} aiment aussi {rel_name}",
                                        ))
                                        excluded_ids.add(tid)
                            except DeezerError:
                                continue
                except DeezerError:
                    continue

        return candidates

    async def _strategy_genre_drift(
        self, profile: UserProfile, excluded_ids: Set[int]
    ) -> List[DiscoveryCandidate]:
        """
        Genre Drift: Genre fingerprint → /editorial releases by genre.

        Discovers new releases in the user's preferred genres.
        source_strategy = 'genre_drift'
        """
        if profile.is_cold_start:
            return []

        candidates = []
        # Take top 3 genres
        top_genres = sorted(
            profile.genre_fingerprint.items(), key=lambda x: x[1], reverse=True
        )[:3]

        for genre_id, weight in top_genres:
            count = max(5, int(weight * 20))
            try:
                releases = await self.deezer.get_editorial_releases(genre_id, limit=count)
                for album in releases:
                    artist = album.get("artist", {})
                    artist_id = artist.get("id")
                    if not artist_id:
                        continue

                    # Get first track from the album
                    try:
                        tracks = await self.deezer.get_album_tracks(str(album["id"]), limit=2)
                        for track in tracks[:1]:
                            tid = track.get("id")
                            if tid and tid not in excluded_ids:
                                candidates.append(DiscoveryCandidate(
                                    deezer_track_id=tid,
                                    deezer_album_id=album.get("id"),
                                    deezer_artist_id=artist_id,
                                    track_title=track.get("title", "Unknown"),
                                    artist_name=artist.get("name", "Unknown"),
                                    album_title=album.get("title"),
                                    cover_url=album.get("cover_medium") or album.get("cover_big"),
                                    duration_ms=(track.get("duration") or 0) * 1000,
                                    release_date=album.get("release_date"),
                                    record_type=album.get("record_type"),
                                    preview_url=track.get("preview"),
                                    source_strategy="genre_drift",
                                    reason_text=f"Nouvelle sortie dans vos genres préférés",
                                ))
                                excluded_ids.add(tid)
                    except DeezerError:
                        continue
            except DeezerError as e:
                logger.debug("Genre drift failed for genre %d: %s", genre_id, e)

        return candidates

    async def _strategy_artist_radio(
        self, profile: UserProfile, excluded_ids: Set[int]
    ) -> List[DiscoveryCandidate]:
        """
        Artist Radio: Top artists → Deezer /artist radio → filter known.

        Uses Deezer's built-in algorithmic radio to discover similar tracks.
        source_strategy = 'artist_radio'
        """
        if profile.is_cold_start:
            return []

        candidates = []
        for artist in profile.top_artists[:5]:
            if not artist.deezer_artist_id:
                continue
            try:
                radio_tracks = await self.deezer.get_artist_radio(str(artist.deezer_artist_id))
                for track in radio_tracks:
                    tid = track.get("id")
                    if tid and tid not in excluded_ids:
                        track_artist = track.get("artist", {})
                        track_album = track.get("album", {})
                        candidates.append(DiscoveryCandidate(
                            deezer_track_id=tid,
                            deezer_album_id=track_album.get("id"),
                            deezer_artist_id=track_artist.get("id", 0),
                            track_title=track.get("title", "Unknown"),
                            artist_name=track_artist.get("name", "Unknown"),
                            album_title=track_album.get("title"),
                            cover_url=track_album.get("cover_medium") or track_album.get("cover_big"),
                            duration_ms=(track.get("duration") or 0) * 1000,
                            release_date=None,
                            record_type=None,
                            preview_url=track.get("preview"),
                            source_strategy="artist_radio",
                            source_artist_name=artist.artist_name,
                            source_deezer_artist_id=artist.deezer_artist_id,
                            reason_text=f"Radio basée sur {artist.artist_name}",
                        ))
                        excluded_ids.add(tid)
            except DeezerError as e:
                logger.debug("Artist radio failed for %s: %s", artist.artist_name, e)

        return candidates

    async def _strategy_wildcard(
        self, profile: UserProfile, excluded_ids: Set[int]
    ) -> List[DiscoveryCandidate]:
        """
        Wildcard: Deezer charts + editorial picks → filter known artists.

        Always runs, even during cold start. Provides serendipitous discoveries.
        source_strategy = 'wildcard'
        """
        candidates = []

        # Known artist IDs to filter out (for non-cold-start users)
        known_artist_ids = {
            a.deezer_artist_id for a in profile.top_artists
            if a.deezer_artist_id
        }

        # Source 1: Global chart tracks
        try:
            chart_tracks = await self.deezer.get_chart_tracks(0, limit=30)
            for track in chart_tracks:
                tid = track.get("id")
                track_artist = track.get("artist", {})
                artist_id = track_artist.get("id")
                if tid and tid not in excluded_ids:
                    # For non-cold-start, filter out known artists
                    if not profile.is_cold_start and artist_id in known_artist_ids:
                        continue
                    track_album = track.get("album", {})
                    candidates.append(DiscoveryCandidate(
                        deezer_track_id=tid,
                        deezer_album_id=track_album.get("id"),
                        deezer_artist_id=artist_id or 0,
                        track_title=track.get("title", "Unknown"),
                        artist_name=track_artist.get("name", "Unknown"),
                        album_title=track_album.get("title"),
                        cover_url=track_album.get("cover_medium") or track_album.get("cover_big"),
                        duration_ms=(track.get("duration") or 0) * 1000,
                        release_date=None,
                        record_type=None,
                        preview_url=track.get("preview"),
                        source_strategy="wildcard",
                        reason_text="Tendance actuelle sur Deezer",
                    ))
                    excluded_ids.add(tid)
        except DeezerError as e:
            logger.debug("Wildcard chart failed: %s", e)

        # Source 2: Editorial selection (global)
        try:
            editorial = await self.deezer.get_editorial_releases(0, limit=15)
            for album in editorial:
                artist = album.get("artist", {})
                artist_id = artist.get("id")
                if not profile.is_cold_start and artist_id in known_artist_ids:
                    continue
                try:
                    tracks = await self.deezer.get_album_tracks(str(album["id"]), limit=1)
                    for track in tracks[:1]:
                        tid = track.get("id")
                        if tid and tid not in excluded_ids:
                            candidates.append(DiscoveryCandidate(
                                deezer_track_id=tid,
                                deezer_album_id=album.get("id"),
                                deezer_artist_id=artist_id or 0,
                                track_title=track.get("title", "Unknown"),
                                artist_name=artist.get("name", "Unknown"),
                                album_title=album.get("title"),
                                cover_url=album.get("cover_medium") or album.get("cover_big"),
                                duration_ms=(track.get("duration") or 0) * 1000,
                                release_date=album.get("release_date"),
                                record_type=album.get("record_type"),
                                preview_url=track.get("preview"),
                                source_strategy="wildcard",
                                reason_text="Sélection éditoriale Deezer",
                            ))
                            excluded_ids.add(tid)
                except DeezerError:
                    continue
        except DeezerError as e:
            logger.debug("Wildcard editorial failed: %s", e)

        return candidates

    # ===================================================================
    # Orchestration
    # ===================================================================

    async def generate_batch(self, user_id: str, target_count: int = 30) -> dict:
        """
        Main pipeline: generate a batch of discovery items.

        1. Check staleness (skip if latest batch < STALENESS_HOURS old)
        2. Acquire per-user mutex
        3. Purge expired files
        4. Build user profile
        5. Run 4 strategies in parallel
        6. Mix, score, diversify
        7. Persist to discovery_items
        8. Trigger pre-downloads (with quota enforcement)

        Returns:
            dict with batch_id, items_generated, predownloads_triggered, is_cold_start
        """
        lock = await self._get_generation_lock(user_id)
        if lock.locked():
            logger.info("generate_batch already running for user %s, skipping", user_id)
            return {"batch_id": None, "items_generated": 0, "predownloads_triggered": 0, "is_cold_start": False, "skipped": True}

        async with lock:
            # Staleness check
            if await self._is_recent_batch_fresh(user_id):
                latest = await self._get_latest_batch_id(user_id)
                return {"batch_id": latest, "items_generated": 0, "predownloads_triggered": 0, "is_cold_start": False, "skipped": True}

            # Purge expired items + files
            purged = await self.purge_expired_discovery_files(user_id)
            if purged > 0:
                logger.info("Purged %d expired discovery files for user %s", purged, user_id)

            # Build profile
            profile = await self.build_user_profile(user_id)

            # Get recently proposed track IDs for dedup
            excluded_ids = await self._get_recently_proposed_track_ids(user_id)

            # Get strategy weights
            weights = await self._get_strategy_weights(user_id)

            # Run 4 strategies in parallel (return_exceptions=True: one failure doesn't crash all)
            results = await asyncio.gather(
                self._strategy_artist_radar(profile, set(excluded_ids)),
                self._strategy_genre_drift(profile, set(excluded_ids)),
                self._strategy_artist_radio(profile, set(excluded_ids)),
                self._strategy_wildcard(profile, set(excluded_ids)),
                return_exceptions=True,
            )

            # Collect candidates (skip exceptions)
            all_candidates = {
                "artist_radar": [],
                "genre_drift": [],
                "artist_radio": [],
                "wildcard": [],
            }
            strategy_names = ["artist_radar", "genre_drift", "artist_radio", "wildcard"]
            for i, result in enumerate(results):
                if isinstance(result, Exception):
                    logger.warning("Strategy %s failed: %s", strategy_names[i], result)
                else:
                    all_candidates[strategy_names[i]] = result

            # Mix strategies according to adaptive weights
            mixed = self._mix_strategies(all_candidates, weights, target_count)

            # Score and diversify (MMR)
            final = self._score_and_diversify(mixed, profile, target_count)

            # Persist
            batch_id = generate_id("batch")
            await self._persist_batch(user_id, batch_id, final)

            # Trigger pre-downloads (with quota enforcement)
            predownloads = await self._trigger_predownloads(user_id, batch_id, final)

            logger.info(
                "Generated batch %s for user %s: %d items, %d predownloads, cold_start=%s",
                batch_id, user_id, len(final), predownloads, profile.is_cold_start,
            )

            return {
                "batch_id": batch_id,
                "items_generated": len(final),
                "predownloads_triggered": predownloads,
                "is_cold_start": profile.is_cold_start,
                "skipped": False,
            }

    def _mix_strategies(
        self,
        candidates_by_strategy: Dict[str, List[DiscoveryCandidate]],
        weights: Dict[str, float],
        target: int,
    ) -> List[DiscoveryCandidate]:
        """
        Mix candidates from all strategies according to adaptive weights.
        Guarantees at least 1 item per strategy that has candidates.
        """
        mixed = []
        seen_track_ids = set()

        # Pass 1: Guarantee representation — 1 best candidate per strategy
        for strat, cands in candidates_by_strategy.items():
            if cands:
                best = max(cands, key=lambda c: c.score if c.score else 0)
                if best.deezer_track_id not in seen_track_ids:
                    mixed.append(best)
                    seen_track_ids.add(best.deezer_track_id)

        # Pass 2: Fill remaining slots proportionally
        remaining = target - len(mixed)
        if remaining > 0:
            for strat, weight in weights.items():
                quota = max(1, int(remaining * weight))
                cands = [
                    c for c in candidates_by_strategy.get(strat, [])
                    if c.deezer_track_id not in seen_track_ids
                ]
                for c in cands[:quota]:
                    mixed.append(c)
                    seen_track_ids.add(c.deezer_track_id)

        return mixed

    def _score_and_diversify(
        self,
        candidates: List[DiscoveryCandidate],
        profile: UserProfile,
        target_count: int,
        max_per_artist: int = 2,
    ) -> List[DiscoveryCandidate]:
        """
        MMR-inspired scoring and diversification.

        1. Score each candidate
        2. Select iteratively, penalizing over-represented artists
        3. Max `max_per_artist` items per artist
        """
        # Score candidates
        for c in candidates:
            freshness = 0.0
            if c.release_date:
                try:
                    rd = datetime.strptime(c.release_date, "%Y-%m-%d")
                    age_days = (datetime.now() - rd).days
                    freshness = max(0, 1.0 - age_days / 60)  # Decays over 60 days
                except ValueError:
                    pass

            c.score = (
                freshness * 25
                + (10 if c.source_strategy == "wildcard" else 5)
                + (c.duration_ms or 0) / 300000 * 5  # Slight preference for reasonable length
            )

        # MMR diversification
        selected = []
        artist_counts: Dict[int, int] = defaultdict(int)

        for c in sorted(candidates, key=lambda c: c.score, reverse=True):
            if len(selected) >= target_count:
                break
            if artist_counts[c.deezer_artist_id] >= max_per_artist:
                continue
            selected.append(c)
            artist_counts[c.deezer_artist_id] += 1

        return selected

    # ===================================================================
    # Persistence
    # ===================================================================

    async def _persist_batch(
        self, user_id: str, batch_id: str, items: List[DiscoveryCandidate]
    ) -> None:
        """Insert discovery items into Supabase."""
        now = datetime.now(timezone.utc).isoformat()
        expires = (datetime.now(timezone.utc) + timedelta(days=14)).isoformat()

        rows = []
        for item in items:
            rows.append({
                "id": generate_id("disc"),
                "user_id": user_id,
                "source_strategy": item.source_strategy,
                "source_artist_name": item.source_artist_name,
                "source_deezer_artist_id": item.source_deezer_artist_id,
                "reason_text": item.reason_text,
                "deezer_track_id": item.deezer_track_id,
                "deezer_album_id": item.deezer_album_id,
                "deezer_artist_id": item.deezer_artist_id,
                "track_title": item.track_title,
                "artist_name": item.artist_name,
                "album_title": item.album_title,
                "cover_url": item.cover_url,
                "duration_ms": item.duration_ms,
                "release_date": item.release_date,
                "record_type": item.record_type,
                "preview_url": item.preview_url,
                "download_status": "pending",
                "score": item.score,
                "batch_id": batch_id,
                "created_at": now,
                "expires_at": expires,
            })

        if rows:
            try:
                supabase.table("discovery_items").insert(rows).execute()
            except Exception as e:
                logger.error("Failed to persist discovery batch %s: %s", batch_id, e)

    # ===================================================================
    # Pre-download Pipeline
    # ===================================================================

    async def _trigger_predownloads(
        self, user_id: str, batch_id: str, items: List[DiscoveryCandidate]
    ) -> int:
        """
        Trigger pre-downloads for top items in the batch.

        Enforces two quotas:
        - MAX_PREDOWNLOADS_PER_BATCH (10) per batch
        - MAX_PREDOWNLOADED_TOTAL (30) across all non-expired items

        If adding new predownloads would exceed the total quota,
        purges the oldest non-adopted items first.
        """
        if not self.download_service:
            logger.warning("No download_service configured, skipping pre-downloads")
            return 0

        # Count existing non-expired pre-downloaded tracks
        try:
            existing = supabase.table("discovery_items") \
                .select("id, created_at, aura_track_id") \
                .eq("user_id", user_id) \
                .eq("is_expired", False) \
                .in_("download_status", ["ready", "downloading"]) \
                .execute()
            current_count = len(existing.data or [])
        except Exception:
            current_count = 0

        # How many new predownloads we can trigger
        available_slots = self.MAX_PREDOWNLOADED_TOTAL - current_count
        batch_limit = min(self.MAX_PREDOWNLOADS_PER_BATCH, available_slots)

        if batch_limit <= 0:
            # Quota exceeded — try to purge oldest non-adopted items
            purged = await self._purge_oldest_non_adopted(user_id, count=5)
            batch_limit = min(self.MAX_PREDOWNLOADS_PER_BATCH, purged)
            if batch_limit <= 0:
                logger.info("Pre-download quota exceeded for user %s, skipping", user_id)
                return 0

        triggered = 0
        for item in items[:batch_limit]:
            try:
                # Build an AURA track ID for the Deezer track
                aura_track_id = f"trk_deezer_{item.deezer_track_id}"

                # Create download job via existing DownloadService
                job = self.download_service.create_job(
                    user_id=user_id,
                    track_id=aura_track_id,
                    provider_name="youtube",
                    source_hint={
                        "title": item.track_title,
                        "artist_name": item.artist_name,
                        "album_title": item.album_title,
                        "cover_uri": item.cover_url,
                        "is_discovery_preload": True,
                    },
                )

                # Update discovery item with download info
                supabase.table("discovery_items") \
                    .update({
                        "download_status": "downloading",
                        "download_job_id": job.id,
                        "aura_track_id": aura_track_id,
                    }) \
                    .eq("batch_id", batch_id) \
                    .eq("deezer_track_id", item.deezer_track_id) \
                    .execute()

                triggered += 1
            except Exception as e:
                logger.warning(
                    "Failed to trigger predownload for %s - %s: %s",
                    item.artist_name, item.track_title, e,
                )

        return triggered

    async def _purge_oldest_non_adopted(self, user_id: str, count: int = 5) -> int:
        """
        Purge the oldest non-adopted pre-downloaded items to make room for new ones.
        An item is "adopted" if the user liked, downloaded, or added it to a playlist.
        """
        try:
            # Get oldest ready items
            items = supabase.table("discovery_items") \
                .select("id, aura_track_id") \
                .eq("user_id", user_id) \
                .eq("download_status", "ready") \
                .eq("is_expired", False) \
                .order("created_at", desc=False) \
                .limit(count * 2) \
                .execute()

            purged = 0
            for item in (items.data or []):
                if purged >= count:
                    break
                # Check if adopted
                fb = supabase.table("discovery_feedback") \
                    .select("was_liked, was_downloaded, was_added_to_playlist") \
                    .eq("discovery_item_id", item["id"]) \
                    .execute()
                if fb.data and any(
                    fb.data[0].get(k) for k in ["was_liked", "was_downloaded", "was_added_to_playlist"]
                ):
                    continue  # Adopted — don't purge

                # Purge the file
                await self._purge_item_file(item)
                purged += 1

            return purged
        except Exception as e:
            logger.warning("Failed to purge oldest non-adopted items: %s", e)
            return 0

    # ===================================================================
    # Cleanup & Expiry
    # ===================================================================

    async def purge_expired_discovery_files(self, user_id: str) -> int:
        """
        Purge audio files for expired discovery items without positive feedback.

        - Sets is_expired = true for items past expires_at
        - Sets file_purged = true and deletes the physical file
          IF st_nlink == 1 (no other reference in the cache)
        - Preserves the DB row for feedback history

        Called at the start of generate_batch and by maintenance scripts.
        """
        now_iso = datetime.now(timezone.utc).isoformat()
        purged = 0

        try:
            # Step 1: Mark expired items
            supabase.table("discovery_items") \
                .update({"is_expired": True}) \
                .eq("user_id", user_id) \
                .eq("is_expired", False) \
                .lt("expires_at", now_iso) \
                .execute()

            # Step 2: Find expired, non-purged items
            expired = supabase.table("discovery_items") \
                .select("id, aura_track_id, download_status") \
                .eq("user_id", user_id) \
                .eq("is_expired", True) \
                .eq("file_purged", False) \
                .in_("download_status", ["ready", "downloading"]) \
                .execute()

            for item in (expired.data or []):
                # Check for positive feedback (adopted = don't purge)
                fb = supabase.table("discovery_feedback") \
                    .select("was_liked, was_downloaded, was_added_to_playlist") \
                    .eq("discovery_item_id", item["id"]) \
                    .execute()
                if fb.data and any(
                    fb.data[0].get(k) for k in ["was_liked", "was_downloaded", "was_added_to_playlist"]
                ):
                    continue

                purged += await self._purge_item_file(item)

        except Exception as e:
            logger.warning("Failed to purge expired discovery files for user %s: %s", user_id, e)

        return purged

    async def _purge_item_file(self, item: dict) -> int:
        """Purge the physical audio file for a discovery item. Returns 1 if purged, 0 otherwise."""
        import os
        from pathlib import Path

        aura_track_id = item.get("aura_track_id")
        if not aura_track_id:
            # No file was downloaded — just mark as purged
            supabase.table("discovery_items") \
                .update({"file_purged": True, "is_expired": True}) \
                .eq("id", item["id"]).execute()
            return 0

        try:
            from app.services.download_service import _get_track_key, _get_global_cache_dir

            track_key = _get_track_key(aura_track_id)
            cache_dir = _get_global_cache_dir()
            cache_audio = cache_dir / f"{track_key}.audio"

            if cache_audio.exists():
                stat = cache_audio.stat()
                if stat.st_nlink == 1:
                    # Only reference — safe to delete
                    cache_audio.unlink()
                    cache_json = cache_audio.with_suffix(".json")
                    if cache_json.exists():
                        cache_json.unlink()
                    logger.info("Purged discovery file: %s", cache_audio.name)
                else:
                    logger.debug(
                        "Skipping purge of %s (st_nlink=%d, still referenced)",
                        cache_audio.name, stat.st_nlink,
                    )

            supabase.table("discovery_items") \
                .update({"file_purged": True, "is_expired": True}) \
                .eq("id", item["id"]).execute()
            return 1
        except Exception as e:
            logger.warning("Failed to purge file for discovery item %s: %s", item["id"], e)
            return 0

    # ===================================================================
    # Feedback & Adaptive Weights
    # ===================================================================

    async def record_feedback(self, user_id: str, item_id: str, action: str) -> None:
        """
        Record a user action on a discovery item.

        Actions: seen, played, completed, skipped, liked, playlist_add, downloaded
        """
        action_map = {
            "seen": {"was_seen": True, "first_seen_at": datetime.now(timezone.utc).isoformat()},
            "played": {"was_played": True, "first_played_at": datetime.now(timezone.utc).isoformat()},
            "completed": {"was_completed": True},
            "skipped": {"was_skipped": True},
            "liked": {"was_liked": True},
            "playlist_add": {"was_added_to_playlist": True},
            "downloaded": {"was_downloaded": True},
        }

        updates = action_map.get(action)
        if not updates:
            return

        updates["updated_at"] = datetime.now(timezone.utc).isoformat()
        fb_id = generate_id("fb")

        try:
            # Upsert feedback row
            supabase.table("discovery_feedback").upsert(
                {
                    "id": fb_id,
                    "user_id": user_id,
                    "discovery_item_id": item_id,
                    **updates,
                },
                on_conflict="user_id,discovery_item_id",
            ).execute()
        except Exception as e:
            logger.warning("Failed to record feedback for item %s: %s", item_id, e)

    async def recompute_weights(self, user_id: str) -> None:
        """
        Recompute strategy weights as normalized CONVERSION RATES.

        rate(strategy) = positive_score / max(1, seen_count)

        Floor of 0.05 per strategy to maintain exploration.
        """
        strategies = ["artist_radar", "genre_drift", "artist_radio", "wildcard"]
        rates: Dict[str, float] = {}
        total_seen_all = 0

        for strat in strategies:
            try:
                # Count seen items for this strategy
                seen_resp = supabase.table("discovery_items") \
                    .select("id", count="exact") \
                    .eq("user_id", user_id) \
                    .eq("source_strategy", strat) \
                    .execute()
                
                item_ids_resp = supabase.table("discovery_items") \
                    .select("id") \
                    .eq("user_id", user_id) \
                    .eq("source_strategy", strat) \
                    .execute()
                item_ids = [d["id"] for d in (item_ids_resp.data or [])]

                if not item_ids:
                    rates[strat] = 0.0
                    continue

                # Count seen feedback
                seen_fb = supabase.table("discovery_feedback") \
                    .select("id", count="exact") \
                    .eq("was_seen", True) \
                    .in_("discovery_item_id", item_ids) \
                    .execute()
                seen_count = len(seen_fb.data or [])
                total_seen_all += seen_count

                # Sum positive feedback score
                fb_resp = supabase.table("discovery_feedback") \
                    .select("was_completed, was_liked, was_added_to_playlist, was_downloaded, was_skipped") \
                    .in_("discovery_item_id", item_ids) \
                    .execute()

                raw_score = 0.0
                for fb in (fb_resp.data or []):
                    if fb.get("was_completed"):
                        raw_score += 5
                    if fb.get("was_liked"):
                        raw_score += 8
                    if fb.get("was_added_to_playlist"):
                        raw_score += 10
                    if fb.get("was_downloaded"):
                        raw_score += 12
                    if fb.get("was_skipped"):
                        raw_score -= 3

                rates[strat] = raw_score / max(1, seen_count)
            except Exception as e:
                logger.warning("Failed to compute rate for strategy %s: %s", strat, e)
                rates[strat] = 0.0

        # Normalize with floor
        total = sum(max(0, v) for v in rates.values())
        if total <= 0 or total_seen_all < 5:
            # Not enough data — keep defaults
            return

        weights = {}
        for strat in strategies:
            weights[strat] = max(0.05, max(0, rates[strat]) / total)

        # Re-normalize after applying floors
        w_total = sum(weights.values())
        weights = {k: v / w_total for k, v in weights.items()}

        try:
            supabase.table("discovery_strategy_weights").upsert({
                "user_id": user_id,
                "artist_radar_weight": weights["artist_radar"],
                "genre_drift_weight": weights["genre_drift"],
                "artist_radio_weight": weights["artist_radio"],
                "wildcard_weight": weights["wildcard"],
                "total_feedback_count": total_seen_all,
                "updated_at": datetime.now(timezone.utc).isoformat(),
            }).execute()
        except Exception as e:
            logger.warning("Failed to update strategy weights for user %s: %s", user_id, e)

    # ===================================================================
    # Helpers
    # ===================================================================

    async def _get_strategy_weights(self, user_id: str) -> Dict[str, float]:
        """Load adaptive strategy weights, falling back to defaults."""
        try:
            resp = supabase.table("discovery_strategy_weights") \
                .select("*") \
                .eq("user_id", user_id) \
                .execute()
            if resp.data:
                row = resp.data[0]
                return {
                    "artist_radar": row["artist_radar_weight"],
                    "genre_drift": row["genre_drift_weight"],
                    "artist_radio": row["artist_radio_weight"],
                    "wildcard": row["wildcard_weight"],
                }
        except Exception:
            pass
        return {
            "artist_radar": 0.45,
            "genre_drift": 0.25,
            "artist_radio": 0.20,
            "wildcard": 0.10,
        }

    async def _get_recently_proposed_track_ids(self, user_id: str) -> Set[int]:
        """
        Get Deezer track IDs proposed in the last DEDUP_DAYS days,
        EXCEPT items that were never seen (was_seen=false) — those can be re-proposed.
        """
        cutoff = (datetime.now(timezone.utc) - timedelta(days=self.DEDUP_DAYS)).isoformat()
        try:
            proposed = supabase.table("discovery_items") \
                .select("id, deezer_track_id") \
                .eq("user_id", user_id) \
                .gte("created_at", cutoff) \
                .execute()

            if not proposed.data:
                return set()

            item_ids = [d["id"] for d in proposed.data]

            # Find which items were seen
            seen = supabase.table("discovery_feedback") \
                .select("discovery_item_id") \
                .eq("was_seen", True) \
                .in_("discovery_item_id", item_ids) \
                .execute()
            seen_item_ids = {d["discovery_item_id"] for d in (seen.data or [])}

            # Only exclude track IDs that were actually seen
            return {
                d["deezer_track_id"] for d in proposed.data
                if d["id"] in seen_item_ids
            }
        except Exception as e:
            logger.warning("Failed to get recently proposed tracks: %s", e)
            return set()

    async def _is_recent_batch_fresh(self, user_id: str) -> bool:
        """Check if the most recent batch is still fresh (< STALENESS_HOURS old)."""
        cutoff = (datetime.now(timezone.utc) - timedelta(hours=self.STALENESS_HOURS)).isoformat()
        try:
            resp = supabase.table("discovery_items") \
                .select("batch_id") \
                .eq("user_id", user_id) \
                .gte("created_at", cutoff) \
                .limit(1) \
                .execute()
            return bool(resp.data)
        except Exception:
            return False

    async def _get_latest_batch_id(self, user_id: str) -> Optional[str]:
        """Get the batch_id of the most recent batch."""
        try:
            resp = supabase.table("discovery_items") \
                .select("batch_id") \
                .eq("user_id", user_id) \
                .order("created_at", desc=True) \
                .limit(1) \
                .execute()
            if resp.data:
                return resp.data[0]["batch_id"]
        except Exception:
            pass
        return None

    async def _get_related_artists_cached(self, deezer_artist_id: int) -> List[dict]:
        """
        Get related artists with a 7-day cache in artist_graph_cache.
        Falls back to live Deezer API on cache miss.
        """
        cache_cutoff = (datetime.now(timezone.utc) - timedelta(days=7)).isoformat()

        # Check cache
        try:
            cached = supabase.table("artist_graph_cache") \
                .select("*") \
                .eq("source_deezer_artist_id", deezer_artist_id) \
                .gte("cached_at", cache_cutoff) \
                .execute()
            if cached.data:
                return cached.data
        except Exception:
            pass

        # Cache miss — fetch from Deezer
        try:
            related = await self.deezer.get_artist_related(str(deezer_artist_id), limit=15)
        except DeezerError as e:
            logger.debug("Failed to get related artists for %d: %s", deezer_artist_id, e)
            return []

        # Persist to cache
        now_iso = datetime.now(timezone.utc).isoformat()
        rows = []
        for r in related:
            rows.append({
                "source_deezer_artist_id": deezer_artist_id,
                "related_deezer_artist_id": r.get("id"),
                "related_artist_name": r.get("name", "Unknown"),
                "related_artist_picture": r.get("picture_medium"),
                "related_nb_fan": r.get("nb_fan"),
                "cached_at": now_iso,
            })

        if rows:
            try:
                supabase.table("artist_graph_cache").upsert(rows).execute()
            except Exception as e:
                logger.debug("Failed to cache artist graph: %s", e)

        return related

    async def get_discovery_feed(self, user_id: str) -> dict:
        """
        Get the current discovery feed for a user.
        Returns non-expired items ordered by score.
        """
        try:
            resp = supabase.table("discovery_items") \
                .select("*") \
                .eq("user_id", user_id) \
                .eq("is_expired", False) \
                .order("score", desc=True) \
                .execute()

            items = resp.data or []

            # Vérification physique : si un fichier audio a été supprimé ou purgé du disque,
            # rétrograder download_status pour éviter que le client tente de streamer un fichier 404
            from app.services.download_service import _find_globally_cached_track
            for item in items:
                if item.get("download_status") == "ready" and item.get("aura_track_id"):
                    cached = _find_globally_cached_track(item["aura_track_id"])
                    if not cached:
                        item["download_status"] = "failed"

            # Get latest batch_id
            batch_id = items[0]["batch_id"] if items else None

            # Check staleness
            is_stale = not await self._is_recent_batch_fresh(user_id)

            return {
                "items": items,
                "batch_id": batch_id,
                "is_stale": is_stale,
                "total_count": len(items),
            }
        except Exception as e:
            logger.error("Failed to get discovery feed for user %s: %s", user_id, e)
            return {"items": [], "batch_id": None, "is_stale": True, "total_count": 0}
