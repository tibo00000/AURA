# Sync Batch API

## Objectif
Definir les contrats HTTP batch concrets du moteur de synchronisation AURA entre Android et le cloud.

## Positionnement
- `docs/server/api-contract.md` reste le contrat REST general du backend.
- `docs/server/sync-conflict-resolution.md` reste la source de verite des regles de conflit.
- Ce document fixe le transport HTTP concret du moteur de sync automatique.

## Principes generaux
- Tous les endpoints de ce document exigent une authentification.
- Tous les endpoints sont scopes a l'utilisateur authentifie.
- Les operations de sync sont idempotentes.
- La source de verite des mutations client est `sync_outbox`.
- Les tokens de sync sont opaques et ne doivent jamais etre interpretes cote client.

## Endpoints
- `POST /me/sync/bootstrap`
- `POST /me/sync/push-batch`
- `POST /me/sync/pull-batch`

## Enveloppe de reponse canonique
```json
{
  "data": {},
  "error": null,
  "meta": {
    "request_id": "req_123"
  }
}
```

## Types canoniques

### `SyncToken`
```json
{
  "value": "st_01JABCTOKEN",
  "issued_at": "2026-04-02T18:50:43Z"
}
```

### `SyncOperation`
```json
{
  "operation_id": "op_01JABCXYZ",
  "entity_type": "playlist",
  "entity_id": "pl_123",
  "operation_type": "update",
  "device_id": "android_pixel_01",
  "occurred_at": "2026-04-02T18:42:39+02:00",
  "base_server_updated_at": "2026-04-02T16:30:00Z",
  "payload": {}
}
```

### `SyncOperationResult`
```json
{
  "operation_id": "op_01JABCXYZ",
  "entity_type": "playlist",
  "entity_id": "pl_123",
  "status": "merged",
  "server_updated_at": "2026-04-02T16:43:10Z",
  "resolved_entity": {},
  "conflict": null
}
```

### `ServerChange`
```json
{
  "change_id": "chg_01JABC001",
  "entity_type": "playlist",
  "entity_id": "pl_123",
  "change_type": "upsert",
  "server_updated_at": "2026-04-02T16:43:10Z",
  "payload": {}
}
```

## `POST /me/sync/bootstrap`
- Usage : hydrater un appareil apres connexion, reinstallation ou perte du token local.
- Auth : requise.
- Regle : cette route renvoie un snapshot restreint des donnees necessaires pour relancer l'experience utilisateur, sans exporter tout l'historique analytique.

### Request body
```json
{
  "device_id": "android_pixel_01",
  "app_version": "0.1.0",
  "capabilities": {
    "supports_batch_push": true,
    "supports_batch_pull": true
  }
}
```

### Response `data`
```json
{
  "sync_token": {
    "value": "st_01JABCTOKEN",
    "issued_at": "2026-04-02T18:50:43Z"
  },
  "snapshot": {
    "user_settings": {
      "sync_enabled": true,
      "online_search_enabled": true,
      "online_search_network_policy": "wifi_only",
      "stats_sync_network_policy": "wifi_only",
      "updated_at": "2026-04-02T18:30:00Z"
    },
    "playlists": [],
    "track_likes": [],
    "playback_snapshot": null
  }
}
```

### Reponse `meta`
```json
{
  "request_id": "req_boot_001",
  "snapshot_version": "snap_01JABC"
}
```

### Regles
- Le snapshot contient :
  - `user_settings`
  - playlists et leurs items
  - likes
  - dernier `playback_snapshot`
- Le snapshot n'inclut pas par defaut :
  - `history_items`
  - `listening_sessions`
  - `playback_events`
  - `user_track_stats`
  - `download_jobs`
- Ces jeux de donnees sont recuperes ensuite via `pull-batch`.

### Erreurs attendues
- `unauthorized`
- `internal_error`

## `POST /me/sync/push-batch`
- Usage : envoyer un lot d'operations locales issues de `sync_outbox`.
- Auth : requise.
- Regles :
  - les operations sont traitees dans l'ordre du tableau
  - l'idempotence se fait par `operation_id`
  - la requete n'est pas atomique au niveau du lot complet
  - chaque operation recoit son resultat propre

### Request body
```json
{
  "device_id": "android_pixel_01",
  "batch_id": "batch_push_001",
  "sent_at": "2026-04-02T18:50:43+02:00",
  "operations": [
    {
      "operation_id": "op_pl_001",
      "entity_type": "playlist",
      "entity_id": "pl_123",
      "operation_type": "update",
      "device_id": "android_pixel_01",
      "occurred_at": "2026-04-02T18:42:39+02:00",
      "base_server_updated_at": "2026-04-02T16:30:00Z",
      "payload": {
        "name": "Driving",
        "is_pinned": true
      }
    }
  ]
}
```

### Response `data`
```json
{
  "batch_id": "batch_push_001",
  "results": [
    {
      "operation_id": "op_pl_001",
      "entity_type": "playlist",
      "entity_id": "pl_123",
      "status": "applied",
      "server_updated_at": "2026-04-02T16:50:45Z",
      "resolved_entity": {
        "id": "pl_123",
        "name": "Driving",
        "is_pinned": true,
        "updated_at": "2026-04-02T16:50:45Z"
      },
      "conflict": null
    }
  ],
  "next_pull_token": {
    "value": "st_01JABCNEXT",
    "issued_at": "2026-04-02T16:50:45Z"
  }
}
```

### Reponse `meta`
```json
{
  "request_id": "req_push_001",
  "received_operations": 1,
  "processed_operations": 1
}
```

### Contraintes
- `operations` minimum : 1
- `operations` maximum recommande : 500
- `entity_type` doit appartenir a la liste canonique documentee dans `docs/server/sync-conflict-resolution.md`
- `device_id` de l'operation doit correspondre au `device_id` du lot

### Erreurs attendues
- `invalid_request` si le lot est vide ou mal forme
- `unauthorized`
- `conflict` au niveau d'une operation individuelle, pas du lot entier

## `POST /me/sync/pull-batch`
- Usage : recuperer les changements serveur depuis un token de sync precedent.
- Auth : requise.
- Regles :
  - le serveur renvoie uniquement des changements scopes a l'utilisateur
  - le client applique ensuite les `ServerChange` dans son stockage local
  - si le token est inconnu ou expire, le serveur demande un nouveau `bootstrap`

### Request body
```json
{
  "device_id": "android_pixel_01",
  "since_token": "st_01JABCTOKEN",
  "limit": 200,
  "entity_types": [
    "user_settings",
    "playlist",
    "playlist_item",
    "track_like",
    "playback_snapshot",
    "history_item",
    "listening_session",
    "playback_event",
    "user_track_stats",
    "download_job"
  ]
}
```

### Response `data`
```json
{
  "changes": [
    {
      "change_id": "chg_01JABC001",
      "entity_type": "playlist",
      "entity_id": "pl_123",
      "change_type": "upsert",
      "server_updated_at": "2026-04-02T16:50:45Z",
      "payload": {
        "id": "pl_123",
        "name": "Driving",
        "is_pinned": true,
        "updated_at": "2026-04-02T16:50:45Z"
      }
    }
  ],
  "next_pull_token": {
    "value": "st_01JABCPULL2",
    "issued_at": "2026-04-02T16:51:00Z"
  },
  "has_more": false
}
```

### Reponse `meta`
```json
{
  "request_id": "req_pull_001",
  "returned_changes": 1
}
```

### `change_type` autorises
- `upsert`
- `delete`

### Regles de payload cote pull
- `playlist` et `playlist_item` renvoient la forme serveur resolue finale
- `track_like` renvoie soit un `upsert` avec `is_liked: true`, soit un `delete`
- `playback_snapshot` renvoie un `upsert` ou `delete`
- `history_item`, `listening_session`, `playback_event` sont additifs et renvoyes en `upsert`
- `user_track_stats` renvoie la version serveur autoritaire calculee
- `download_job` renvoie l'etat serveur du job quand il change

### Erreurs attendues
- `invalid_request`
- `unauthorized`
- `conflict` si `since_token` est expire ou invalide

### Reponse type si token invalide
```json
{
  "data": null,
  "error": {
    "code": "conflict",
    "message": "Sync token is invalid or expired. Bootstrap is required.",
    "retryable": true,
    "details": {
      "reason": "stale_sync_token"
    }
  },
  "meta": {
    "request_id": "req_pull_001"
  }
}
```

## Ordre recommande de sync cote client
1. `POST /me/sync/bootstrap` lors de la premiere connexion d'un appareil ou apres perte du token.
2. `POST /me/sync/push-batch` pour vider `sync_outbox`.
3. `POST /me/sync/pull-batch` pour recuperer les changements serveur.
4. Mise a jour de `last_sync_at` et rotation du `next_pull_token`.

## Regles de mise en oeuvre cote client
- Le client ne supprime une entree `sync_outbox` qu'apres reception d'un resultat `applied`, `merged` ou `ignored_duplicate`.
- Un resultat `conflict` laisse l'entree locale en echec fonctionnel jusqu'a resolution.
- Les `download_job` ne sont jamais modifies localement via sync hors creation ou retry demande.
- Les stats ne doivent etre poussees que si la politique reseau autorise la sync.

## Hors perimetre
- sync de la `priority queue`
- sync de navigation UI
- sync de niveau de scroll
- replication brute de `MediaStore`
