# API Contract

## Objectif
Definir le contrat HTTP de reference du backend AURA pour les capacites online, la sync cloud optionnelle et les jobs asynchrones.

## Principe local-first
- L'application Android reste pleinement fonctionnelle sans backend pour la bibliotheque locale, les playlists locales, la lecture et l'historique local.
- Le backend enrichit l'experience avec la recherche online, les jobs et la sync cloud optionnelle.
- La fusion des resultats locaux et online reste du ressort du client Android.

## Familles d'API

### API online publique
- Utilisee sans compte pour la recherche enrichie et les pages detail online.
- Aucune route publique ne doit etre necessaire pour lire une bibliotheque locale.

### API sync cloud optionnelle
- Utilisee seulement si l'utilisateur active un compte ou la synchronisation cloud.
- Toutes les routes `/me/...` portent des donnees durables utilisateur.
- Les routes resource-oriented de cette section coexistent avec une couche de transport batch de sync decrite dans `docs/server/sync-batch-api.md`.

### API jobs et downloads
- Utilisee quand le backend execute une tache asynchrone.
- Le contrat reste generique tant que la source finale de telechargement n'est pas figee.

## Enveloppe JSON canonique

### Reponse de succes
```json
{
  "data": {},
  "error": null,
  "meta": {}
}
```

### Reponse d'erreur
```json
{
  "data": null,
  "error": {
    "code": "not_found",
    "message": "Requested resource was not found.",
    "retryable": false,
    "details": {}
  },
  "meta": {
    "request_id": "req_123"
  }
}
```

## Regles transverses

### Authentification
- Les routes publiques n'exigent pas d'authentification.
- Les routes `/me/...`, `/downloads` et `/jobs/{id}` exigent un bearer token valide.
- L'absence de compte ne doit jamais empecher le fonctionnement local de l'application.

### Identifiants
- Tous les identifiants exposes par l'API sont des chaines opaques.
- Un identifiant renvoye dans `data` doit pouvoir etre reutilise tel quel par le client.

### Timestamps
- Tous les timestamps HTTP sont en ISO 8601 UTC.
- Les durees restent exprimees en millisecondes.

### Pagination
- Les collections paginees utilisent `limit` et `cursor`.
- La reponse renvoie `meta.next_cursor` quand une page suivante existe.
- Les valeurs par defaut et maximum sont precisees endpoint par endpoint.

### Erreurs
- Les codes d'erreur canoniques sont `invalid_request`, `unauthorized`, `forbidden`, `not_found`, `conflict`, `provider_unavailable`, `rate_limited`, `job_failed`, `internal_error`.
- Une erreur provider ne doit jamais etre decrite comme une erreur locale de bibliotheque.

### Fallback provider
- `GET /search` peut renvoyer un succes partiel avec `meta.partial_failure = true` si un provider externe echoue.
- Les details techniques provider restent encapsules dans `error.details` ou `meta.provider_status`.

## Objets de reponse canoniques

### `TrackSummary`
```json
{
  "id": "trk_aura_123",
  "title": "The Giver",
  "display_artist_name": "Chappell Roan",
  "display_album_title": "Single",
  "duration_ms": 202768,
  "cover_uri": "https://...",
  "is_explicit": false,
  "is_liked": false,
  "is_local_available": false,
  "is_downloaded_by_aura": false
}
```

### `ArtistSummary`
```json
{
  "id": "art_aura_123",
  "name": "Chappell Roan",
  "picture_uri": "https://..."
}
```

### `AlbumSummary`
```json
{
  "id": "alb_aura_123",
  "title": "Single",
  "primary_artist_name": "Chappell Roan",
  "cover_uri": "https://...",
  "release_date": "2025-01-01",
  "track_count": 1
}
```

### `PlaylistSummary`
```json
{
  "id": "pl_123",
  "name": "Driving",
  "cover_uri": "https://...",
  "item_count": 18,
  "is_pinned": false,
  "updated_at": "2026-04-02T10:00:00Z"
}
```

### `PlaybackSnapshot`
```json
{
  "current_track_id": "trk_aura_123",
  "playback_context_type": "playlist",
  "playback_context_id": "pl_123",
  "playback_context_index": 4,
  "position_ms": 81234,
  "shuffle_enabled": false,
  "repeat_mode": "all",
  "updated_at": "2026-04-02T10:00:00Z"
}
```

## API online publique

### `GET /health`
- Usage : verifier que le backend est joignable.
- Auth : non requise.
- Query params : aucun.
- Body : aucun.
- Reponse `data` :
```json
{
  "status": "ok",
  "service": "aura-api",
  "time": "2026-04-02T10:00:00Z"
}
```
- Erreurs attendues : aucune erreur metier.

### `GET /search`
- Usage : lancer une recherche online enrichie sur titres, artistes et albums.
- Auth : non requise.
- Query params :
  - `q` requis, longueur minimale 3
  - `limit_tracks` optionnel, defaut 10, max 25
  - `limit_artists` optionnel, defaut 8, max 20
  - `limit_albums` optionnel, defaut 8, max 20
- Body : aucun.
- Reponse `data` :
```json
{
  "query": "the giver",
  "best_match": {
    "kind": "track",
    "item": {
      "id": "trk_aura_123",
      "title": "The Giver",
      "display_artist_name": "Chappell Roan",
      "display_album_title": "Single",
      "duration_ms": 202768,
      "cover_uri": "https://...",
      "is_explicit": false,
      "is_liked": false,
      "is_local_available": false,
      "is_downloaded_by_aura": false
    }
  },
  "tracks": [],
  "artists": [],
  "albums": []
}
```
- Reponse `meta` :
```json
{
  "request_id": "req_123",
  "partial_failure": false,
  "provider_status": {
    "deezer": "ok"
  }
}
```
- Erreurs attendues :
  - `invalid_request` si `q` est absent ou trop court
  - `provider_unavailable` seulement si aucune reponse exploitable ne peut etre produite
- Regles :
  - cette route ne renvoie que la partie online ou enrichie
  - la fusion avec le local reste cote Android

### `GET /artists/{id}`
- Usage : recuperer la vue detail online d'un artiste deja resolu dans le modele AURA.
- Auth : non requise.
- Path params :
  - `id` identifiant AURA artiste
- Body : aucun.
- Reponse `data` :
```json
{
  "id": "art_aura_123",
  "name": "Chappell Roan",
  "picture_uri": "https://...",
  "summary": "Artist summary",
  "top_tracks": [],
  "albums": []
}
```
- Erreurs attendues :
  - `not_found`
  - `provider_unavailable`

### `GET /albums/{id}`
- Usage : recuperer la vue detail online d'un album deja resolu dans le modele AURA.
- Auth : non requise.
- Path params :
  - `id` identifiant AURA album
- Body : aucun.
- Reponse `data` :
```json
{
  "id": "alb_aura_123",
  "title": "Single",
  "primary_artist_name": "Chappell Roan",
  "cover_uri": "https://...",
  "release_date": "2025-01-01",
  "track_count": 1,
  "tracks": []
}
```
- Erreurs attendues :
  - `not_found`
  - `provider_unavailable`

## API sync cloud optionnelle

### `GET /me/settings`
- Usage : recuperer les preferences synchronisees utilisateur.
- Auth : requise.
- Query params : aucun.
- Body : aucun.
- Reponse `data` :
```json
{
  "sync_enabled": true,
  "online_search_enabled": true,
  "online_search_network_policy": "wifi_only",
  "stats_sync_network_policy": "wifi_only",
  "updated_at": "2026-04-02T10:00:00Z"
}
```
- Erreurs attendues :
  - `unauthorized`
  - `not_found`

### `PATCH /me/settings`
- Usage : modifier les preferences synchronisees utilisateur.
- Auth : requise.
- Body :
```json
{
  "sync_enabled": true,
  "online_search_enabled": true,
  "online_search_network_policy": "wifi_only",
  "stats_sync_network_policy": "wifi_only"
}
```
- Reponse `data` : meme shape que `GET /me/settings`.
- Erreurs attendues :
  - `invalid_request`
  - `unauthorized`

### `GET /me/playlists`
- Usage : lister les playlists synchronisees de l'utilisateur.
- Auth : requise.
- Query params :
  - `limit` optionnel, defaut 20, max 100
  - `cursor` optionnel
- Reponse `data` :
```json
{
  "items": []
}
```
- Reponse `meta` :
```json
{
  "next_cursor": null
}
```
- Les items utilisent la shape `PlaylistSummary`.

### `POST /me/playlists`
- Usage : creer une playlist synchronisee.
- Auth : requise.
- Body :
```json
{
  "name": "Driving",
  "is_pinned": false
}
```
- Reponse `data` : `PlaylistSummary`
- Erreurs attendues :
  - `invalid_request`
  - `unauthorized`

### `GET /me/playlists/{id}`
- Usage : recuperer une playlist synchronisee avec ses items ordonnes.
- Auth : requise.
- Path params :
  - `id` identifiant playlist
- Reponse `data` :
```json
{
  "id": "pl_123",
  "name": "Driving",
  "cover_uri": "https://...",
  "item_count": 18,
  "is_pinned": false,
  "updated_at": "2026-04-02T10:00:00Z",
  "items": [
    {
      "playlist_item_id": "pli_1",
      "track": {
        "id": "trk_aura_123",
        "title": "The Giver",
        "display_artist_name": "Chappell Roan",
        "display_album_title": "Single",
        "duration_ms": 202768,
        "cover_uri": "https://...",
        "is_explicit": false,
        "is_liked": false,
        "is_local_available": false,
        "is_downloaded_by_aura": false
      },
      "position": 0,
      "added_at": "2026-04-02T10:00:00Z"
    }
  ]
}
```
- Erreurs attendues :
  - `unauthorized`
  - `not_found`

### `PATCH /me/playlists/{id}`
- Usage : modifier le nom, l'etat epingle ou la cover d'une playlist synchronisee.
- Auth : requise.
- Body :
```json
{
  "name": "Driving",
  "is_pinned": true,
  "cover_uri": "https://..."
}
```
- Reponse `data` : `PlaylistSummary`
- Erreurs attendues :
  - `invalid_request`
  - `unauthorized`
  - `not_found`

### `DELETE /me/playlists/{id}`
- Usage : supprimer une playlist synchronisee.
- Auth : requise.
- Body : aucun.
- Reponse `data` :
```json
{
  "deleted": true,
  "id": "pl_123"
}
```
- Erreurs attendues :
  - `unauthorized`
  - `not_found`

### `POST /me/playlists/{id}/tracks`
- Usage : ajouter une piste a une playlist synchronisee.
- Auth : requise.
- Body :
```json
{
  "track_id": "trk_aura_123",
  "position": 5,
  "added_from_context_type": "album",
  "added_from_context_id": "alb_aura_123"
}
```
- Reponse `data` :
```json
{
  "playlist_item_id": "pli_1",
  "playlist_id": "pl_123",
  "track_id": "trk_aura_123",
  "position": 5
}
```
- Erreurs attendues :
  - `invalid_request`
  - `unauthorized`
  - `not_found`

### `PATCH /me/playlists/{id}/items/reorder`
- Usage : reordonner les items d'une playlist synchronisee.
- Auth : requise.
- Body :
```json
{
  "items": [
    {
      "playlist_item_id": "pli_1",
      "position": 0
    },
    {
      "playlist_item_id": "pli_2",
      "position": 1
    }
  ]
}
```
- Reponse `data` :
```json
{
  "playlist_id": "pl_123",
  "updated_at": "2026-04-02T10:00:00Z"
}
```
- Erreurs attendues :
  - `invalid_request`
  - `unauthorized`
  - `not_found`
  - `conflict`

### `DELETE /me/playlists/{id}/tracks/{trackId}`
- Usage : retirer une occurrence d'une piste d'une playlist synchronisee.
- Auth : requise.
- Path params :
  - `id` identifiant playlist
  - `trackId` identifiant piste
- Query params :
  - `playlist_item_id` optionnel mais recommande
- Regle :
  - si plusieurs occurrences du meme `trackId` existent et que `playlist_item_id` est absent, l'API renvoie `conflict`
- Reponse `data` :
```json
{
  "deleted": true,
  "playlist_id": "pl_123",
  "track_id": "trk_aura_123",
  "playlist_item_id": "pli_1"
}
```
- Erreurs attendues :
  - `unauthorized`
  - `not_found`
  - `conflict`

### `PUT /me/tracks/{trackId}/like`
- Usage : creer ou confirmer le like cloud d'une piste.
- Auth : requise.
- Body :
```json
{
  "source_context_type": "search",
  "source_context_id": "search_req_123"
}
```
- Reponse `data` :
```json
{
  "track_id": "trk_aura_123",
  "is_liked": true,
  "liked_at": "2026-04-02T10:00:00Z"
}
```
- Erreurs attendues :
  - `unauthorized`
  - `not_found`

### `DELETE /me/tracks/{trackId}/like`
- Usage : retirer le like cloud d'une piste.
- Auth : requise.
- Body : aucun.
- Reponse `data` :
```json
{
  "track_id": "trk_aura_123",
  "is_liked": false
}
```
- Erreurs attendues :
  - `unauthorized`
  - `not_found`

### `GET /me/history`
- Usage : recuperer l'historique d'ecoute synchronise.
- Auth : requise.
- Query params :
  - `limit` optionnel, defaut 50, max 200
  - `cursor` optionnel
- Reponse `data` :
```json
{
  "items": [
    {
      "id": "hist_1",
      "track_id": "trk_aura_123",
      "played_at": "2026-04-02T10:00:00Z",
      "completion_percent": 92.4,
      "was_skipped": false,
      "source_context_type": "playlist",
      "source_context_id": "pl_123"
    }
  ]
}
```
- Reponse `meta` :
```json
{
  "next_cursor": null
}
```
- Erreurs attendues :
  - `unauthorized`

### `PUT /me/playback-snapshot`
- Usage : enregistrer l'etat de reprise cloud du player.
- Auth : requise.
- Body :
```json
{
  "current_track_id": "trk_aura_123",
  "playback_context_type": "playlist",
  "playback_context_id": "pl_123",
  "playback_context_index": 4,
  "position_ms": 81234,
  "shuffle_enabled": false,
  "repeat_mode": "all"
}
```
- Reponse `data` : `PlaybackSnapshot`
- Erreurs attendues :
  - `invalid_request`
  - `unauthorized`

### `GET /me/playback-snapshot`
- Usage : recuperer l'etat de reprise cloud du player.
- Auth : requise.
- Query params : aucun.
- Body : aucun.
- Reponse `data` : `PlaybackSnapshot` ou `null` si aucun snapshot n'existe encore.
- Erreurs attendues :
  - `unauthorized`

### `POST /me/stats/batch`
- Usage : envoyer un lot de sessions, d'evenements et d'agregats d'ecoute.
- Auth : requise.
- Regle :
  - le client n'appelle cette route que si la politique reseau autorise la sync stats, typiquement en Wi-Fi
- Body :
```json
{
  "sessions": [
    {
      "id": "sess_1",
      "started_at": "2026-04-02T09:00:00Z",
      "ended_at": "2026-04-02T09:40:00Z",
      "source_type": "playlist",
      "source_id": "pl_123",
      "network_type": "wifi",
      "total_listening_ms": 2400000
    }
  ],
  "events": [
    {
      "id": "evt_1",
      "session_id": "sess_1",
      "track_id": "trk_aura_123",
      "event_type": "skip",
      "occurred_at": "2026-04-02T09:05:00Z",
      "position_start_ms": 0,
      "position_end_ms": 45000,
      "completion_percent": 22.1,
      "skip_reason": "next",
      "liked_during_playback": false
    }
  ],
  "track_stats": [
    {
      "track_id": "trk_aura_123",
      "period_type": "day",
      "period_start": "2026-04-02",
      "play_count": 3,
      "skip_count": 1,
      "complete_play_count": 2,
      "last_played_at": "2026-04-02T09:30:00Z",
      "total_listening_ms": 480000,
      "average_completion_percent": 81.4,
      "queue_add_count": 1,
      "playlist_add_count": 0,
      "is_liked": false
    }
  ]
}
```
- Reponse `data` :
```json
{
  "accepted": true,
  "received_sessions": 1,
  "received_events": 1,
  "received_track_stats": 1
}
```
- Erreurs attendues :
  - `invalid_request`
  - `unauthorized`

### Transport batch de sync
- Les ressources `/me/...` restent l'API lisible et actionnable manuellement.
- Le transport canonique pour un moteur de sync automatique est documente dans `docs/server/sync-batch-api.md`.
- Les endpoints batch permettent :
  - un bootstrap initial apres connexion ou reinstallation
  - l'envoi idempotent d'operations issues de `sync_outbox`
  - la recuperation des changements serveur depuis un token de sync

## API jobs et downloads

### `GET /downloads`
- Usage : lister les jobs de telechargement de l'utilisateur.
- Auth : requise.
- Query params :
  - `status` optionnel
  - `limit` optionnel, defaut 20, max 100
  - `cursor` optionnel
- Reponse `data` :
```json
{
  "items": [
    {
      "id": "job_1",
      "track_id": "trk_aura_123",
      "provider_name": "backend",
      "status": "running",
      "progress_percent": 42.0,
      "error_code": null,
      "error_message": null,
      "attempt_count": 1,
      "created_at": "2026-04-02T10:00:00Z",
      "updated_at": "2026-04-02T10:03:00Z"
    }
  ]
}
```
- Reponse `meta` :
```json
{
  "next_cursor": null
}
```
- Erreurs attendues :
  - `unauthorized`

### `POST /downloads`
- Usage : demander au backend de preparer ou lancer un telechargement pour une piste AURA.
- Auth : requise.
- Body :
```json
{
  "track_id": "trk_aura_123",
  "source_hint": {
    "provider_name": "deezer",
    "provider_track_id": "12345"
  }
}
```
- Regles :
  - `source_hint` est optionnel
  - le backend reste libre de choisir la meilleure strategie interne
  - aucun flow de selection de source n'est fige dans ce contrat
- Reponse `data` :
```json
{
  "job_id": "job_1",
  "track_id": "trk_aura_123",
  "status": "queued"
}
```
- Erreurs attendues :
  - `invalid_request`
  - `unauthorized`
  - `not_found`
  - `job_failed`

### `POST /downloads/{id}/retry`
- Usage : relancer un job de telechargement echoue ou annule.
- Auth : requise.
- Path params :
  - `id` identifiant job
- Body : aucun.
- Reponse `data` :
```json
{
  "job_id": "job_1",
  "status": "queued",
  "attempt_count": 2
}
```
- Erreurs attendues :
  - `unauthorized`
  - `not_found`
  - `conflict`

### `GET /jobs/{id}`
- Usage : consulter l'etat d'un job asynchrone user-scoped.
- Auth : requise.
- Path params :
  - `id` identifiant job
- Reponse `data` :
```json
{
  "id": "job_1",
  "kind": "download",
  "status": "running",
  "progress_percent": 42.0,
  "result": null,
  "error": null,
  "created_at": "2026-04-02T10:00:00Z",
  "updated_at": "2026-04-02T10:03:00Z"
}
```
- Erreurs attendues :
  - `unauthorized`
  - `not_found`

## Hors perimetre explicite de cette API
- la bibliotheque locale issue de `MediaStore`
- la fusion local + online dans `Search`
- la `priority queue`
- la pile de navigation
- le niveau de scroll
