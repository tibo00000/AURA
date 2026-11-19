from types import SimpleNamespace
from unittest.mock import MagicMock

import pytest

from app.services import download_service
from app.services.audio_matching import artist_match_score, title_match_score
from app.services.download_service import DownloadService
from app.services.exceptions import ProviderUnavailable, RequiresResolutionException


@pytest.mark.parametrize("artists,found", [
    ("Luidji, Ryan Koffi", ["Luidji"]), ("Népal, Nekfeu", ["Népal"]),
    ("Werenoi, Hamza", ["Werenoi"]), ("Luv Resval, Lujipeka", ["Luv Resval", "Lujipeka"]),
])
def test_same_principal_artist_can_match_a_catalogue_collaboration(artists, found):
    assert artist_match_score(artists, found) >= 80


def test_a_guest_alone_and_an_unrelated_artist_are_not_sufficient():
    assert artist_match_score("Luidji, Ryan Koffi", ["Ryan Koffi"]) == 0
    assert artist_match_score("Georgio", ["Unknown"]) == 0


def test_featuring_is_not_a_version_but_live_and_remix_are():
    assert title_match_score("Hadès (feat. Lujipeka)", "Hades") == 100
    assert title_match_score("Monde", "Monde (Live)") == 0
    assert title_match_score("On ira", "On ira - Remix") == 0
    assert title_match_score("Song (Instrumental)", "Song") == 0


def test_monde_uses_album_track_with_matching_principal_artist(monkeypatch):
    music = SimpleNamespace(search=MagicMock(return_value=[{
        "title": "Saison 00", "artists": [{"name": "Luidji"}], "browseId": "fixture",
    }]), get_album=MagicMock(return_value={"tracks": [
        {"title": "Monde (Live)", "videoId": "wrong000000"},
        {"title": "Monde", "videoId": "fixture0000"},
    ]}))
    monkeypatch.setattr(download_service, "ytmusic", music)
    service = DownloadService.__new__(DownloadService)
    assert service._resolve_official_video_id("Luidji, Ryan Koffi", "Monde", "Saison 00", strict=True) == "fixture0000"


@pytest.mark.parametrize("stage", ["album", "songs", "candidates"])
def test_provider_outages_are_recoverable_in_strict_resolution(monkeypatch, stage):
    def search(query, filter):
        if (stage == "album" and filter == "albums") or stage != "album":
            raise TimeoutError("synthetic")
        return []
    monkeypatch.setattr(download_service, "ytmusic", SimpleNamespace(search=search))
    service = DownloadService.__new__(DownloadService)
    service._candidates_cache = {}
    if stage == "candidates":
        service._smart_song_search = lambda *args, **kwargs: None
    with pytest.raises(ProviderUnavailable):
        service._resolve_official_video_id("Artist", "Title", "Album" if stage == "album" else None, strict=True)


def test_successful_empty_catalogue_response_is_unresolved_not_a_network_failure(monkeypatch):
    monkeypatch.setattr(download_service, "ytmusic", SimpleNamespace(search=lambda *args, **kwargs: []))
    service = DownloadService.__new__(DownloadService)
    service._candidates_cache = {}
    with pytest.raises(RequiresResolutionException) as error:
        service._resolve_official_video_id("Artist", "Title", strict=True)
    assert error.value.candidates == []
