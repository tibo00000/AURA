"""Private suggestions only: metadata can block a duplicate, never merge audio."""
from app.services.audio_matching import artist_match_score, title_match_score


class ExistingCopyNeedsConfirmation(Exception):
    pass


def existing_copy_candidates(intent):
    from app.api.routes.sync_files import _list_playable_sync_files
    from app.api.routes.sync_files import _base_dir
    import hashlib
    personal = _base_dir()/hashlib.sha256(intent['user_id'].encode()).hexdigest()
    if not personal.exists():return []
    metadata = intent.get('metadata') or {}
    if metadata.get('identity_decision') == 'distinct':return []
    title, artist = metadata.get('title'), metadata.get('artist')
    if not title or not artist:return []
    credits = metadata.get('artists') or []
    expected = credits[0].get('name') if credits else artist
    result=[]
    for candidate in _list_playable_sync_files(intent['user_id']):
        if candidate.get('track_id')==intent['track_id']:continue
        names=[c.get('name') for c in candidate.get('artists') or [] if isinstance(c,dict)]
        names=names or [candidate.get('artist_name')]
        if title_match_score(title,candidate.get('title') or '')==100 and artist_match_score(expected,names)>=98:
            result.append(candidate)
    return result
