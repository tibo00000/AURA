"""Ordered, explicit artist identities; punctuation never separates artists."""
from typing import Literal
from pydantic import BaseModel, Field, model_validator
from app.core.aura_id_codec import build_aura_id


class ArtistCredit(BaseModel):
    id: str = Field(min_length=1, max_length=250)
    name: str = Field(min_length=1, max_length=512)
    provider: Literal['spotify', 'deezer'] | None = None
    provider_id: str | None = Field(default=None, max_length=64)
    role: Literal['primary', 'performer', 'featured'] = 'performer'
    picture_uri: str | None = Field(default=None, max_length=1200)

    @model_validator(mode='after')
    def verified_reference(self):
        if not self.name.strip():raise ValueError('Empty artist')
        if self.provider is not None:
            if not self.provider_id or self.id != build_aura_id('artist', self.provider, self.provider_id):
                raise ValueError('Artist reference does not match its ID')
        return self


def deezer_artist_credits(data):
    entries = [data.get('artist')] + list(data.get('contributors') or [])
    result=[];seen=set()
    for artist in entries:
        if not isinstance(artist,dict) or not artist.get('id') or not artist.get('name'):continue
        identifier=str(artist['id'])
        if not identifier.isdigit() or identifier in seen:continue
        seen.add(identifier)
        result.append(ArtistCredit(
            id=build_aura_id('artist','deezer',identifier),name=artist['name'],provider='deezer',provider_id=identifier,
            role='primary' if not result else ('featured' if artist.get('role')=='Featured' else 'performer'),
            picture_uri=artist.get('picture_medium') or artist.get('picture_xl') or artist.get('picture'),
        ).model_dump(exclude_none=True))
    return result
