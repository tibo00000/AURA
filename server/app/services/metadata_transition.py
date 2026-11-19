"""Versioned metadata-only transition. Never downloads or merges recordings."""
import unicodedata
from app.core.aura_id_codec import parse_aura_id, build_aura_id
from app.schemas.artist_credits import deezer_artist_credits
from app.services.audio_matching import title_match_score
from app.providers.deezer.exceptions import DeezerNotFound

VERSION = 1


def label(value):
    return ' '.join(''.join(c for c in unicodedata.normalize('NFKD', value or '').casefold()
        if not unicodedata.combining(c)).split())


async def resolve_metadata(client, track_id, metadata):
    """Exact provider reference first; otherwise require a unique strict match."""
    try:
        ref = parse_aura_id(track_id, expected_kind="track")
    except ValueError:
        ref = None
    if ref is not None and ref.provider_name == 'deezer':
        try:
            candidate = await client.get_track(ref.provider_id)
        except DeezerNotFound:
            return {'status':'unidentified','version':VERSION}
    else:
        title, artist = metadata.get('title'), metadata.get('artist_name') or metadata.get('artist')
        if not title or not artist:return {'status': 'unidentified', 'version': VERSION}
        # The principal is a search hint only. It never becomes an artist identity
        # or an automatic recording link inferred from commas.
        known = metadata.get('artists') or []
        primary = known[0]['name'] if known else artist
        query = 'track:"' + title.replace('"',' ') + '"'
        data = await client.search(query, resource_type='track', limit=25)
        matches=[]
        for row in data.get('data') or []:
            name=(row.get('artist') or {}).get('name')
            duration=(row.get('duration') or 0)*1000
            expected=metadata.get('duration_ms')
            album=metadata.get('album_title') or metadata.get('album')
            if title_match_score(title,row.get('title') or '') != 100:continue
            # Without an exact principal name, do not guess a credit list.
            if label(name) != label(primary) and not label(primary).startswith(label(name)+', '):continue
            if not expected or not duration or abs(expected-duration)>2500:continue
            if album and label(album) != label((row.get('album') or {}).get('title')):continue
            matches.append(row)
        ids={str(row.get('id')) for row in matches}
        if len(ids)!=1:return {'status': 'needs_review' if ids else 'unidentified', 'version': VERSION}
        candidate=await client.get_track(next(iter(ids)))
    credits=deezer_artist_credits(candidate)
    if not credits:return {'status': 'unidentified', 'version': VERSION}
    if metadata.get('artist_correction') and label(metadata.get('artist_name')) not in {
        label(', '.join(a['name'] for a in credits)), *(label(a['name']) for a in credits)
    }:
        return {'status':'needs_review','version':VERSION}
    if metadata.get('title') and title_match_score(metadata['title'],candidate.get('title') or '') != 100:
        return {'status':'needs_review','version':VERSION}
    album=candidate.get('album') or {}
    return {'status':'resolved','version':VERSION,'artists':credits,'title':candidate.get('title'),
        'album_title':album.get('title'),'duration_ms':(candidate.get('duration') or 0)*1000,
        'album_id':build_aura_id('album','deezer',str(album['id'])) if album.get('id') else None,
        'cover_uri':album.get('cover_big') or album.get('cover_medium')}


def fill_missing(existing, result):
    updated=dict(existing)
    if result['status']=='resolved':
        if not updated.get('artists') and updated.get('artists_origin') != 'manual' and not updated.get('artist_correction'):
            updated['artists']=result['artists']
        if not updated.get('artist_id') and result['artists'] and updated.get('artists_origin')!='manual' and not updated.get('artist_correction'):updated['artist_id']=result['artists'][0]['id']
        if not updated.get('artist_name') and result['artists']:updated['artist_name']=', '.join(a['name'] for a in result['artists'])
        if not updated.get('album_id') and result.get('album_id') and (not updated.get('album_title') or label(updated['album_title'])==label(result.get('album_title'))):updated['album_id']=result['album_id']
        for key in ('title','album_title','duration_ms','cover_uri'):
            if not updated.get(key) and result.get(key):updated[key]=result[key]
    updated['metadata_transition_version']=VERSION
    updated['metadata_transition_status']=result['status']
    return updated


async def resume_transitions(client):
    """Only resume explicit operator requests, in the API process and its storage lock."""
    import asyncio, hashlib, json, logging, os
    from app.api.routes.sync_files import _base_dir, _list_playable_sync_files, _paths, _read_metadata
    from app.services.download_lifecycle import blocking
    from app.services.storage_safety import STORAGE_LOCK, stage_json
    requests = _base_dir()/'_metadata_transitions'/'requests'
    if not requests.is_dir() or requests.is_symlink():return
    logger=logging.getLogger(__name__)

    def save(path, value):
        staged=stage_json(path,value);os.replace(staged,path)

    for job_path in sorted(requests.glob('*.json')):
        if job_path.is_symlink():continue
        job=await blocking(lambda: json.loads(job_path.read_text(encoding='utf-8')))
        owner=job.get('user_id')
        if not isinstance(owner,str) or job_path.stem != hashlib.sha256(owner.encode()).hexdigest():continue
        if job.get('version')!=VERSION or job.get('status')=='completed':continue
        done=job.setdefault('completed',{})
        for track_id in job['track_ids']:
            if track_id in done:continue
            while True:
                try:
                    _,path=_paths(owner,track_id)
                    before=await blocking(_read_metadata,path)
                    if not before:
                        done[track_id]='removed';break
                    if before.get('metadata_transition_version',0)>=VERSION:
                        done[track_id]=before.get('metadata_transition_status','resolved');break
                    result=await resolve_metadata(client,track_id,before)
                    def commit():
                        with STORAGE_LOCK:
                            current=_read_metadata(path)
                            if current != before:return False
                            backup=requests.parent/'backups'/job_path.stem/path.name
                            backup.parent.mkdir(parents=True,exist_ok=True,mode=0o700)
                            if not backup.exists():
                                backup.write_bytes(path.read_bytes());backup.chmod(0o600)
                            updated=fill_missing(before,result)
                            from datetime import datetime,timezone
                            updated['updated_at']=datetime.now(timezone.utc).isoformat()
                            save(path,updated)
                            return True
                    if not await blocking(commit):continue
                    done[track_id]=result['status']
                    break
                except asyncio.CancelledError:raise
                except Exception as exc:
                    job['status']='waiting_network'
                    job['last_error']=type(exc).__name__
                    await blocking(save,job_path,job)
                    logger.warning('Metadata transition waiting for provider (%s)',type(exc).__name__)
                    await asyncio.sleep(60)
            job['status']='running'
            job.pop('last_error',None)
            await blocking(save,job_path,job)
            if len(done)%25==0:logger.info('Metadata transition processed %s/%s',len(done),len(job['track_ids']))
            await asyncio.sleep(.35)
        job['status']='completed'
        job['counts']={status:sum(value==status for value in done.values()) for status in set(done.values())}
        await blocking(save,job_path,job)
        logger.info('Metadata transition completed: %s',job['counts'])
