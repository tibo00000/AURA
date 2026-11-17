# API Contract

## Objectif
Definir le contrat HTTP de reference du backend AURA pour les capacites online, la sync cloud optionnelle et les jobs asynchrones.

## Principe local-first
- L'application Android reste pleinement fonctionnelle sans backend pour la bibliotheque locale, les playlists locales, la lecture et l'historique local.
- Le backend enrichit l'experience avec la recherche online, les jobs et la sync cloud optionnelle.
- La fusion des resultats locaux et online reste du ressort du client Android.
- La recherche online v1 passe exclusivement par le backend AURA. Android ne doit pas appeler Deezer directement.

## Familles d'API

### API online publique
- Utilisee sans compte pour la recherche enrichie et les pages detail online.
- Aucune route publique ne doit etre necessaire pour lire une bibliotheque locale.
- Ces routes exigent un backend always-on du point de vue produit pour eviter une latence de reveil dans `Search`.

### API sync cloud optionnelle
- Utilisee seulement si l'utilisateur active un compte ou la synchronisation cloud.
- Toutes les routes `/me/...` portent des donnees durables utilisateur.
- Les routes resource-oriented de cette section coexistent avec une couche de transport batch de sync decrite dans `docs/server/sync-batch-api.md`.

### API jobs et downloads
- Utilisee quand le backend execute une tache asynchrone.
- Le contrat reste generique tant que la source finale de telechargement n'est pas figee.

## Enveloppe JSON canonique

Les routes privées exigent un access token Supabase vérifié selon
`docs/server/security-and-secrets.md`, par Bearer ou paramètre `token` pour la lecture audio.
Les erreurs d'authentification conservent le format FastAPI HTTP 401 `{"detail": "..."}`
et `WWW-Authenticate: Bearer`. Aucun identifiant propriétaire fixe n'est accepté.
Les routes historiques `/test` ne font pas partie de l'application principale (HTTP 404).

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
- Les routes online publiques renvoient des identifiants AURA backend pour `tracks`, `albums` et `artists`, et non des identifiants provider natifs.
- Les identifiants derives de `MediaStore` (`track:local:*`) ou des slugs `Room` locaux ne doivent pas etre envoyes aux routes online publiques comme s'ils etaient canoniques cote backend.
- La fusion entre un resultat online et une entite deja presente localement se fait par matching de metadonnees et mappings persistants, pas par egalite d'ID.
- Les identifiants provider (`provider_track_id`, `provider_album_id`, `provider_artist_id`) restent encapsules derriere le backend ou les tables de mapping.
- Avant `SRV-004`, le backend peut utiliser un codec opaque stateless resolu cote serveur pour chaîner `/search` vers `/artists/{id}` et `/albums/{id}`, tant que le client traite toujours ces IDs comme opaques.

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
  "is_downloaded_by_aura": false,
  "is_available_in_cloud": false
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
  "track_count": 1,
  "release_type": "single"
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
  - `best_match` represente le meilleur candidat online connu du backend au moment de la reponse
  - le backend doit choisir `best_match` parmi `tracks`, `artists` et `albums` selon la meilleure correspondance textuelle avec `q`, sans favoriser systematiquement les pistes
  - Android reste libre de remplacer ce `best_match` par un hero local plus pertinent apres fusion avec la bibliotheque locale
  - cette route ne renvoie que la partie online ou enrichie
  - la fusion avec le local reste cote Android
  - les `id` renvoyes dans `best_match`, `tracks`, `artists` et `albums` sont des identifiants backend a reutiliser pour les routes online suivantes
  - cette route ne tente pas d'exposer ni de normaliser les identifiants `MediaStore` du device

### `GET /resolve/artist`
- Usage : resoudre un artiste local ou textuel vers un identifiant backend et des metadonnees minimales d'enrichissement.
- Auth : non requise.
- Query params :
  - `name` requis
- Body : aucun.
- Reponse `data` :
```json
{
  "resolved": true,
  "match_confidence": 0.93,
  "artist": {
    "id": "art_aura_123",
    "name": "Daft Punk",
    "picture_uri": "https://..."
  }
}
```
- Regles :
  - cette route sert a enrichir une entite locale sans supposer qu'elle possede deja un ID backend
  - Android persiste ensuite le resultat dans `Room` et peut ouvrir `/artists/{id}` si necessaire

### `GET /resolve/album`
- Usage : resoudre un album local ou textuel vers un identifiant backend et des metadonnees minimales d'enrichissement.
- Auth : non requise.
- Query params :
  - `title` requis
  - `artist_name` optionnel mais fortement recommande
- Body : aucun.
- Reponse `data` :
```json
{
  "resolved": true,
  "match_confidence": 0.91,
  "album": {
    "id": "alb_aura_123",
    "title": "Discovery",
    "primary_artist_name": "Daft Punk",
    "cover_uri": "https://...",
    "release_date": "2001-03-12",
    "track_count": 14,
    "release_type": "album"
  }
}
```
- Regles :
  - cette route sert a enrichir une entite locale sans supposer qu'elle possede deja un ID backend
  - Android persiste ensuite le resultat dans `Room` et peut ouvrir `/albums/{id}` si necessaire

### `GET /artists/{id}`
- Usage : recuperer la vue detail online d'un artiste deja resolu dans le modele AURA.
- Auth : non requise.
- Path params :
  - `id` identifiant AURA backend d'artiste, typiquement recu depuis `GET /search`
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
- Regles :
  - ce payload doit contenir au minimum les champs necessaires au hero online `Artist`
  - si une information hero critique n'est pas connue, la route renvoie `null` explicitement plutot qu'une structure implicite
- Erreurs attendues :
  - `not_found`
  - `provider_unavailable`
- Regle :
  - cette route n'accepte pas un identifiant `artist:{slug}` local Android comme preuve d'identite online sans mapping backend prealable

### `GET /albums/{id}`
- Usage : recuperer la vue detail online d'un album deja resolu dans le modele AURA.
- Auth : non requise.
- Path params :
  - `id` identifiant AURA backend d'album, typiquement recu depuis `GET /search`
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
  "release_type": "single",
  "tracks": []
}
```
- Regles :
  - ce payload doit contenir au minimum les champs necessaires au hero online `Album`
  - si une information hero critique n'est pas connue, la route renvoie `null` explicitement plutot qu'une structure implicite
- Erreurs attendues :
  - `not_found`
  - `provider_unavailable`
- Regle :
  - cette route n'accepte pas un identifiant `album:{slug_artiste}:{slug_album}` local Android comme preuve d'identite online sans mapping backend prealable

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

### Playlists — contrat REST réellement exposé (SEC-002.2)

Toutes ces routes exigent le JWT vérifié de l'utilisateur. L'enveloppe reste
~~~
{"data": ..., "error": null, "meta": null}
~~~
Succès 200 ; validation de schéma 422 ; authentification manquante/invalide 401.
Les mutations sur une playlist absente ou étrangère donnent 404. Une erreur SQL donne
500 avec message générique, sans détail privé.

| Route | Requête | Réponse data |
|---|---|---|
| GET /me/playlists | Aucun paramètre de pagination implémenté | Liste de PlaylistResponse, chacune avec ses items ordonnés par position |
| POST /me/playlists | id et name requis ; cover_uri optionnel ; is_pinned=false par défaut | PlaylistResponse avec items vides |
| PATCH /me/playlists/{id} | name, cover_uri, is_pinned optionnels | PlaylistResponse avec items |
| DELETE /me/playlists/{id} | Aucun body | PlaylistResponse supprimée et ses items |
| POST /me/playlists/{id}/tracks | id, track_id et position requis ; added_from_context_type/id optionnels | PlaylistItemResponse |
| DELETE /me/playlists/{id}/tracks/{trackId} | Aucun body ni sélecteur d'occurrence | Liste des PlaylistItemResponse supprimés, vide si aucune occurrence |

PlaylistResponse : id, user_id, name, cover_uri nullable, is_pinned, created_at, updated_at,
items. PlaylistItemResponse : id, playlist_id, track_id, position, added_at,
added_from_context_type/id nullables. Les dates sont des chaînes ISO 8601.

Il n'existe pas de GET /me/playlists/{id} (405 pour cette méthode). Lire la liste puis
chercher l'ID propre. Aucun PATCH REST /items/reorder n'est implémenté ; le réordonnancement
utilise le transport batch existant décrit dans docs/server/sync-batch-api.md.

SEC-002.2 préserve les sémantiques implémentées : ajout REST sans décalage des positions
(une position occupée échoue en 500, sans mutation), suppression de **toutes** les occurrences
d'une piste sans compacter les positions, PATCH ignorant les null et conservant false.
Ces trois mutations passent par une seule transaction RPC SECURITY INVOKER sous verrou du
parent propriétaire, y compris son updated_at calculé dans PostgreSQL après verrou et
strictement supérieur à sa valeur précédente. Les contrats batch et marqueurs restent inchangés.
Une erreur SQL intermédiaire annule la mutation ; une perte de réponse réseau après COMMIT
reste une issue inconnue pour le client. Migration et backend correspondants doivent être
activés ensemble après les validations du runbook SEC-002.1/SEC-002.2, sans changement client.

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

### `GET /me/playlists/{id}/acquisitions`

Renvoie la disponibilité vérifiée des pistes et l’état des intentions du
compte. `POST` sur la même route inscrit les intentions manquantes.
`POST ?retry_track_id=…` demande une reprise pour une piste de la playlist.
La présence du fichier est vérifiée par le même résolveur que le streaming.
`track_id` reste l’identifiant de la piste dans Aura. Quand l’audio
disponible provient d’un rattachement fournisseur confirmé, le champ
facultatif `audio_track_id` donne l’identifiant à utiliser pour le flux.
Le client utilise cet identifiant seulement avec `availability=available`.
Avec une copie cloud déjà présente sous `track_id`, la réponse privilégie
cette copie. `source_identity_ambiguous` signale des rattachements
contradictoires qui empêchent l’acquisition automatique.
Une erreur de vérification rend l’appel indisponible et n’établit pas
l’absence d’un fichier.

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

### Synchronisation des fichiers audio locaux

Les routes suivantes permettent aux utilisateurs de pousser (upload) et tirer (download) des fichiers audio locaux physiques pour assurer le transfert direct entre leurs différents appareils via le backend.

#### `POST /me/sync/files/{track_id}`
- Usage : Uploader un fichier audio local physique vers le stockage du backend.
- Auth : requise.
- Path params :
  - `track_id` requis.
- Body : `multipart/form-data` contenant la clé `file` (le fichier audio).
- SEC-005 :50Mio audio maximum,51Mio avec enveloppe multipart, un seul fichier,
  huit champs de64Kio ; authentification/admission avant spooling. Un upload
  reste personnel et ne remplace pas l'audio partagé global.
- Erreurs supplémentaires :413 dépassement effectif,429 troisième upload simultané
  du même compte (`Retry-After: 10`),507 réserve disque insuffisante. Le compteur
  ne se fonde pas sur Content-Length et les erreurs ne tronquent pas l'ancien audio.
- En-tête informatif `X-Aura-Upload-Warnings` : codes séparés par virgule
  `files_10m`, `bytes_24h` (>15 admissions/10min, >2Gio reçus/24h). Les avertissements
  n'altèrent pas le201 ni l'enveloppe existante. Compteurs mémoire, fenêtres octets
  par minute (approximation inférieure à60s), remis à zéro au redémarrage.
  Exposé par CORS ; aucun affichage client nouveau n'est implémenté dans ce lot.
- Reponse `data` :
```json
{
  "track_id": "track_local_123",
  "synced": true,
  "size_bytes": 10485760,
  "updated_at": "2026-06-10T20:25:00Z"
}
```

#### `GET /me/sync/files/{track_id}`
- Usage : Télécharger le fichier audio physique précédemment poussé sur le backend.
- Auth : requise.
- Path params :
  - `track_id` requis.
- Reponse : Le flux binaire du fichier audio (`audio/mpeg` ou `audio/mp4`).

#### `DELETE /me/sync/files/{track_id}`
- Usage : Supprimer le fichier audio physique stocké sur le backend.
- Auth : requise.
- Path params :
  - `track_id` requis.
- Reponse `data` :
```json
{
  "track_id": "track_local_123",
  "deleted": true
}
```

#### `GET /me/sync/files`
- Usage : Lister les fichiers audio locaux stockés et synchronisés sur le cloud pour cet utilisateur.
- Auth : requise.
- Reponse `data` :
```json
{
  "items": [
    {
      "track_id": "track_local_123",
      "size_bytes": 10485760,
      "uploaded_at": "2026-06-10T20:25:00Z"
    }
  ]
}
```

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
      "operation_type": "download",
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

Le champ `operation_type` vaut `download` pour le flux standard et `manual_reassign` lorsqu'une
réassignation explicite de la source audio a été demandée. Seule cette dernière autorise le client
à invalider son cache de streaming ; elle ne constitue jamais une instruction de supprimer un
fichier hors-ligne privé.

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
- SEC-005.1 : un job `manual_reassign` reçoit HTTP400 sans mutation, car le
  schéma ne conserve pas la vidéo choisie. Utiliser une nouvelle réassignation
  explicite ou `POST /downloads/{id}/resolve` avec un `video_id` non vide ; cette
  résolution conserve le remplacement global et son historique, sans ancien cache.
  Une source perdue ne déclenche jamais une recherche implicite de remplacement.
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

### `DELETE /downloads/{id}`
- Auth : JWT utilisateur vérifié, propriétaire obligatoire.
- Succès : HTTP 204, corps vide. Le job est supprimé logiquement et sa tentative ne
  peut plus publier. L'arrêt physique et le nettoyage privé peuvent se poursuivre
  en arrière-plan ; aucun suivi HTTP 202 n'est introduit.
- Un ID adverse et un ID absent produisent le même HTTP 404. Répéter DELETE après
  suppression peut donc produire 404 sans effet supplémentaire.
- ID dangereux : HTTP 400. Mutation concurrente incompatible : HTTP 409.
- Indisponibilité SQL ou suppression non démontrée : HTTP 503, sans faux succès.
- Les fichiers de travail sont ciblés exactement ; la bibliothèque personnelle et
  le cache partagé ne sont pas supprimés.

Pour create/retry/resolve/reassign, une représentation SQL non vérifiable donne
503, même si son commit est ensuite confirmé. Le service conserve la supervision
du travail confirmé ; le client peut relire la liste de jobs puis réessayer. Une
issue SQL inconnue interdit une nouvelle tentative concurrente pour la même paire
utilisateur/piste. Les formes des réponses de succès restent inchangées.

Le GET fichier relit le propriétaire et l'état sous la même garde que DELETE.
La sélection est sans backfill ni publication ; un flux autorisé avant DELETE
peut se terminer. Une réponse 204 ne révoque pas les fichiers déjà synchronisés.

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

## Historique collaboratif des remplacements audio — SEC-005

`POST /downloads/reassign` conserve son contrat201/job existant et accepte en
plus `reason` optionnel : `incorrect_audio`, `quality`, `wrong_version`, `other`.
Le champ n'est pas du texte libre ; source_hint, tokens, URL source et email ne
sont pas recopiés dans l'historique. L'acteur vient du JWT vérifié, jamais du payload.

`GET /me/audio-replacements` exige un JWT valide. Cet historique des actions
**globales partagées** est consultable par les membres authentifiés, y compris
pour une action d'un autre membre ; il ne contient aucune bibliothèque privée.
Filtres optionnels `track_id` (512 caractères max), `before` (séquence exclusive)
et `limit` (1..100, défaut50). Enveloppe :

```json
{"data":{"items":[{"sequence":1,"event_id":"internal-attempt-id","job_id":"job_example","track_id":"trk_example","actor_id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","operation":"shared_audio_replace","reason":"incorrect_audio","created_at":"2026-10-22T18:00:00+00:00","updated_at":"2026-10-22T18:00:10+00:00","old_sha256":"empreinte-SHA256-ancienne","new_sha256":"empreinte-SHA256-nouvelle","status":"succeeded"}],"next_cursor":null}}
```

Les empreintes sont nulles avant disponibilité des audios. États : `pending`
(tentative engagée), `publishing` (intention durable avant effets), `succeeded`
(audio/propagation/statut job confirmés), `failed` (pas d'effet établi), `partial`
(effet incomplet), `cancelled`, `indeterminate` (interruption/confirmation manquante).
Le statut d'échec d'un job ne signifie donc pas toujours absence de tout effet
global : consulter l'événement partiel, ne pas réessayer aveuglément en supposant
un rollback global. Une panne du journal avant publication interdit celle-ci.
Une panne après effet ne produit jamais un faux événement de succès.
Erreur503 si le journal est indisponible. Aucun endpoint de suppression de
l'historique ou de restauration d'anciens fichiers n'est ajouté. Une interface
future peut lire cette API et afficher les en-têtes ; aucun client n'est modifié.

## Hors perimetre explicite de cette API
- la bibliotheque locale issue de `MediaStore`
- la fusion local + online dans `Search`
- la `priority queue`
- la pile de navigation
- le niveau de scroll
