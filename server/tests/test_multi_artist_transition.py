import asyncio,json
from types import SimpleNamespace
from unittest.mock import AsyncMock
import pytest
from pydantic import ValidationError
from app.core.aura_id_codec import build_aura_id
from app.schemas.artist_credits import ArtistCredit,deezer_artist_credits
from app.services.metadata_transition import fill_missing,resolve_metadata
from app.services.recording_candidates import existing_copy_candidates


def track():
    return {'id':10,'title':'Song','duration':180,'artist':{'id':1,'name':'Tyler, The Creator'},
            'contributors':[{'id':1,'name':'Tyler, The Creator'},{'id':2,'name':'Guest'}],
            'album':{'id':20,'title':'Album','cover_medium':'https://example.invalid/cover'}}


def test_punctuation_does_not_create_fake_artists_and_order_is_stable():
    credits=deezer_artist_credits(track())
    assert [a['name'] for a in credits]==['Tyler, The Creator','Guest']
    assert [a['role'] for a in credits]==['primary','performer']
    assert ArtistCredit.model_validate(credits[0]).id==build_aura_id('artist','deezer','1')
    with pytest.raises(ValidationError):ArtistCredit.model_validate({**credits[0],'provider_id':'2'})


def test_an_exact_reference_refreshes_credits_without_touching_existing_edits():
    client=SimpleNamespace(get_track=AsyncMock(return_value=track()),search=AsyncMock())
    result=asyncio.run(resolve_metadata(client,build_aura_id('track','deezer','10'),{'title':'Song'}))
    assert result['status']=='resolved'
    original={'title':'My title','artist_name':'My artist','album_title':'My album','cover_uri':'file:local',
        'artists_origin':'manual','artists':[{'id':'manual','name':'My artist'}]}
    updated=fill_missing(original,result)
    for key in original:assert updated[key]==original[key]
    assert updated['duration_ms']==180000 and updated['metadata_transition_version']==1
    assert fill_missing(updated,result)==updated
    client.search.assert_not_called()


def test_an_ambiguous_search_is_not_a_metadata_merge():
    other=track()|{'id':11}
    client=SimpleNamespace(search=AsyncMock(return_value={'data':[track(),other]}),get_track=AsyncMock())
    result=asyncio.run(resolve_metadata(client,'track:local:1',{'title':'Song','artist_name':'Tyler, The Creator','duration_ms':180000,'album_title':'Album'}))
    assert result['status']=='needs_review'
    client.get_track.assert_not_called()


def test_known_private_copy_blocks_only_automatic_acquisition(monkeypatch,tmp_path):
    from app.api.routes import sync_files
    monkeypatch.setattr(sync_files,'_base_dir',lambda:tmp_path)
    import hashlib
    owner='account';(tmp_path/hashlib.sha256(owner.encode()).hexdigest()).mkdir()
    monkeypatch.setattr(sync_files,'_list_playable_sync_files',lambda _: [
        {'track_id':'old','title':'Song','artist_name':'Tyler, The Creator','artists':deezer_artist_credits(track())}])
    intent={'user_id':owner,'track_id':'new','metadata':{'title':'Song','artist':'Tyler, The Creator, Guest','artists':deezer_artist_credits(track())}}
    assert len(existing_copy_candidates(intent))==1
    intent['metadata']['identity_decision']='distinct'
    assert not existing_copy_candidates(intent)


def test_compare_and_set_metadata_preserves_manual_change_and_audio(monkeypatch,tmp_path):
    from app.api.routes import sync_files
    from fastapi import HTTPException
    monkeypatch.setattr(sync_files,'_base_dir',lambda:tmp_path)
    audio,metadata=sync_files._paths('account','trk_fixture')
    audio.write_bytes(b'unchanged audio')
    original={'track_id':'trk_fixture','title':'My title','updated_at':'newer-edit'}
    metadata.write_text(json.dumps(original),encoding='utf-8')
    with pytest.raises(HTTPException) as error:
        sync_files.apply_metadata_update('account','trk_fixture',{'fill_missing_only':True,'expected_updated_at':'old','title':'Provider title','artists':deezer_artist_credits(track())})
    assert error.value.status_code==409
    assert json.loads(metadata.read_text())==original
    result=sync_files.apply_metadata_update('account','trk_fixture',{'fill_missing_only':True,'expected_updated_at':'newer-edit','title':'Provider title','artists':deezer_artist_credits(track()),'duration_ms':180000})
    assert result['title']=='My title' and len(result['artists'])==2
    assert audio.read_bytes()==b'unchanged audio'

def test_operator_transition_resumes_once_without_rewriting_completed_tracks(monkeypatch,tmp_path):
    import hashlib
    from app.api.routes import sync_files
    from app.services import metadata_transition as transition
    monkeypatch.setattr(sync_files,'_base_dir',lambda:tmp_path)
    owner='account'; ownerhash=hashlib.sha256(owner.encode()).hexdigest()
    requests=tmp_path/'_metadata_transitions'/'requests'; requests.mkdir(parents=True)
    jobpath=requests/(ownerhash+'.json')
    identifiers=['track:local:1','track:local:2']
    jobpath.write_text(json.dumps({'user_id':owner,'version':1,'status':'pending','track_ids':identifiers}),encoding='utf-8')
    paths=[]
    for identifier in identifiers:
        audio,path=sync_files._paths(owner,identifier);audio.write_bytes(b'original audio')
        path.write_text(json.dumps({'track_id':identifier,'title':'Manual title','artist_name':'My artist'}),encoding='utf-8');paths.append((audio,path))
    result={'status':'resolved','version':1,'artists':deezer_artist_credits(track()),'duration_ms':180000}
    resolver=AsyncMock(side_effect=[result,asyncio.CancelledError()]);monkeypatch.setattr(transition,'resolve_metadata',resolver)
    monkeypatch.setattr(asyncio,'sleep',AsyncMock())
    with pytest.raises(asyncio.CancelledError):asyncio.run(transition.resume_transitions(object()))
    first=paths[0][1].read_bytes()
    assert json.loads(jobpath.read_text())['completed']=={identifiers[0]:'resolved'}
    resolver=AsyncMock(return_value=result);monkeypatch.setattr(transition,'resolve_metadata',resolver)
    asyncio.run(transition.resume_transitions(object()))
    assert resolver.await_count==1
    assert paths[0][1].read_bytes()==first
    assert json.loads(jobpath.read_text())['status']=='completed'
    assert len(list((requests.parent/'backups'/ownerhash).glob('*.json')))==2
    asyncio.run(transition.resume_transitions(object()))
    assert resolver.await_count==1
    assert all(audio.read_bytes()==b'original audio' for audio,_ in paths)
    assert all(json.loads(path.read_text())['title']=='Manual title' for _,path in paths)


def test_exact_reference_not_found_is_final_but_network_failure_is_retryable():
    from app.providers.deezer.exceptions import DeezerNotFound, DeezerTimeout
    identifier=build_aura_id('track','deezer','10')
    client=SimpleNamespace(get_track=AsyncMock(side_effect=DeezerNotFound('removed')))
    assert asyncio.run(resolve_metadata(client,identifier,{}))['status']=='unidentified'
    client.get_track=AsyncMock(side_effect=DeezerTimeout('temporary'))
    with pytest.raises(DeezerTimeout):asyncio.run(resolve_metadata(client,identifier,{}))


def test_transition_does_not_reintroduce_an_explicitly_corrected_wrong_artist():
    client=SimpleNamespace(get_track=AsyncMock(return_value=track()))
    original={'title':'Song','artist_name':'Confirmed manual artist','artist_correction':{'version':1}}
    result=asyncio.run(resolve_metadata(client,build_aura_id('track','deezer','10'),original))
    assert result['status']=='needs_review'
    updated=fill_missing(original,result)
    assert 'artists' not in updated and 'artist_id' not in updated
    assert updated['artist_name']==original['artist_name']


def test_missing_guests_can_be_completed_but_partial_results_cannot_erase_credits():
    credits=deezer_artist_credits(track())
    old={'artists':credits[:1]}
    full=fill_missing(old,{'status':'resolved','artists':credits})
    assert full['artists']==credits
    assert fill_missing(full,{'status':'resolved','artists':credits[:1]})['artists']==credits
    assert fill_missing({'artists':credits[:1],'artists_origin':'manual'}, {'status':'resolved','artists':credits})['artists']==credits[:1]
