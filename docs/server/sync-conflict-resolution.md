# Sync Conflict Resolution

## Objectif
Definir la strategie canonique de synchronisation entre le client Android et le cloud AURA, avec les payloads exacts par entite et les regles de resolution des conflits.

## Relation avec le contrat API
- `docs/server/api-contract.md` decrit les endpoints REST lisibles par un humain.
- `docs/server/sync-batch-api.md` decrit le transport HTTP batch concret de cette strategie.
- Ce document decrit la couche de sync canonique qui peut etre transportee :
  - soit par les endpoints REST existants
  - soit par un endpoint batch futur
  - soit par la `sync_outbox` locale et sa traduction serveur
- Les payloads ci-dessous sont la source de verite pour la logique de conflit, meme si l'API publique les enveloppe differemment.

## Principes generaux
- Chaque mutation cliente porte un `operation_id` stable et unique.
- Toute mutation est idempotente par `operation_id`.
- Toute mutation porte un `device_id` et un `occurred_at`.
- Les entites additives ne sont pas ecrasees. Elles sont dedupliquees.
- Les entites derivables sont recalculees cote serveur plutot que fusionnees aveuglement.
- Les entites server-authoritative ne sont pas modifiees directement par le client.
- Une reponse de sync doit toujours renvoyer un resultat explicite : `applied`, `merged`, `conflict` ou `ignored_duplicate`.

## Entites server-authoritative
Le client ne doit pas envoyer de mutation directe pour :
- `artists`
- `albums`
- `tracks`
- `track_source_links`
- `download_jobs` une fois qu'un job est cree cote serveur

Ces entites sont enrichies par les providers, les workers ou la couche serveur.

## Enveloppe canonique de mutation
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

## Champs communs
- `operation_id` : identifiant unique de la mutation, genere cote client.
- `entity_type` : type canonique AURA.
- `entity_id` : identifiant stable de l'entite cible.
- `operation_type` : nature de la mutation.
- `device_id` : identifiant stable du device emetteur.
- `occurred_at` : date reelle de l'action cote client.
- `base_server_updated_at` : version serveur connue par le client au moment de l'edition. Omit pour les flux additifs.
- `payload` : contenu propre a l'entite.

## Enveloppe canonique de resultat
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

## Objet `conflict`
```json
{
  "reason": "base_outdated",
  "server_entity": {},
  "client_payload": {},
  "retryable": true
}
```

## Regles par entite

### `user_settings`
Strategie :
- fusion champ par champ
- last-write-wins par champ sur `occurred_at`
- si deux appareils changent des champs differents, le resultat est `merged`
- si deux appareils changent le meme champ, la valeur la plus recente gagne

Payload exact :
```json
{
  "operation_id": "op_set_001",
  "entity_type": "user_settings",
  "entity_id": "default",
  "operation_type": "patch",
  "device_id": "android_pixel_01",
  "occurred_at": "2026-04-02T18:42:39+02:00",
  "base_server_updated_at": "2026-04-02T16:30:00Z",
  "payload": {
    "sync_enabled": true,
    "online_search_network_policy": "wifi_only"
  }
}
```

### `playlist`
Strategie :
- fusion champ par champ sur `name`, `is_pinned`, `cover_uri`
- last-write-wins par champ sur `occurred_at`
- la suppression d'une playlist gagne sur toute edition plus ancienne
- une edition sur playlist deja supprimee retourne `conflict`

Payload exact :
```json
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
    "is_pinned": true,
    "cover_uri": "https://cdn.example/cover.jpg"
  }
}
```

Payload de suppression :
```json
{
  "operation_id": "op_pl_del_001",
  "entity_type": "playlist",
  "entity_id": "pl_123",
  "operation_type": "delete",
  "device_id": "android_pixel_01",
  "occurred_at": "2026-04-02T18:42:39+02:00",
  "base_server_updated_at": "2026-04-02T16:30:00Z",
  "payload": {}
}
```

### `playlist_item` ajout
Strategie :
- l'identite canonique est `playlist_item_id`, pas `track_id`
- un ajout est idempotent par `operation_id`
- si deux appareils ajoutent la meme piste, deux items distincts peuvent exister
- si la position cible est deja prise, le serveur recompacte les positions et repond `merged`

Payload exact :
```json
{
  "operation_id": "op_pli_add_001",
  "entity_type": "playlist_item",
  "entity_id": "pli_789",
  "operation_type": "insert",
  "device_id": "android_pixel_01",
  "occurred_at": "2026-04-02T18:42:39+02:00",
  "base_server_updated_at": "2026-04-02T16:30:00Z",
  "payload": {
    "playlist_id": "pl_123",
    "track_id": "trk_aura_123",
    "position": 5,
    "added_at": "2026-04-02T18:42:39+02:00",
    "added_from_context_type": "album",
    "added_from_context_id": "alb_aura_123"
  }
}
```

### `playlist_item` suppression
Strategie :
- la suppression doit viser `playlist_item_id`
- si l'item est deja supprime, la reponse est `ignored_duplicate`
- une suppression par `track_id` seul est interdite dans la couche de sync canonique

Payload exact :
```json
{
  "operation_id": "op_pli_del_001",
  "entity_type": "playlist_item",
  "entity_id": "pli_789",
  "operation_type": "delete",
  "device_id": "android_pixel_01",
  "occurred_at": "2026-04-02T18:42:39+02:00",
  "base_server_updated_at": "2026-04-02T16:30:00Z",
  "payload": {
    "playlist_id": "pl_123"
  }
}
```

### `playlist_reorder`
Strategie :
- le reorder est traite comme une operation distincte de type `playlist_reorder`
- il utilise un `base_order_token` calcule a partir de l'ordre serveur connu par le client
- si `base_order_token` est stale, le serveur renvoie `conflict`
- le client doit alors recharger la playlist et renvoyer un nouvel ordre
- aucun merge automatique n'est tente pour deux reorders concurrents

Payload exact :
```json
{
  "operation_id": "op_pl_reorder_001",
  "entity_type": "playlist_reorder",
  "entity_id": "pl_123",
  "operation_type": "reorder",
  "device_id": "android_pixel_01",
  "occurred_at": "2026-04-02T18:42:39+02:00",
  "payload": {
    "base_order_token": "ord_7ac4e2",
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
}
```

### `track_like`
Strategie :
- la sync transporte l'etat voulu final avec `is_liked`
- last-write-wins sur `occurred_at`
- si deux appareils envoient le meme etat final, la seconde operation est `ignored_duplicate`

Payload exact :
```json
{
  "operation_id": "op_like_001",
  "entity_type": "track_like",
  "entity_id": "trk_aura_123",
  "operation_type": "set",
  "device_id": "android_pixel_01",
  "occurred_at": "2026-04-02T18:42:39+02:00",
  "base_server_updated_at": "2026-04-02T16:30:00Z",
  "payload": {
    "track_id": "trk_aura_123",
    "is_liked": true,
    "source_context_type": "search",
    "source_context_id": "search_req_123"
  }
}
```

### `playback_snapshot`
Strategie :
- last-write-wins sur `occurred_at`
- le snapshot le plus recent remplace l'ancien
- aucune fusion structurelle n'est tentee

Payload exact :
```json
{
  "operation_id": "op_snap_001",
  "entity_type": "playback_snapshot",
  "entity_id": "default",
  "operation_type": "replace",
  "device_id": "android_pixel_01",
  "occurred_at": "2026-04-02T18:42:39+02:00",
  "base_server_updated_at": "2026-04-02T16:30:00Z",
  "payload": {
    "current_track_id": "trk_aura_123",
    "playback_context_type": "playlist",
    "playback_context_id": "pl_123",
    "playback_context_index": 4,
    "position_ms": 81234,
    "shuffle_enabled": false,
    "repeat_mode": "all"
  }
}
```

### `history_item`
Strategie :
- `history_item` est additif
- deduplication par `entity_id`
- aucune suppression ni edition cote client

Payload exact :
```json
{
  "operation_id": "op_hist_001",
  "entity_type": "history_item",
  "entity_id": "hist_001",
  "operation_type": "insert",
  "device_id": "android_pixel_01",
  "occurred_at": "2026-04-02T18:42:39+02:00",
  "payload": {
    "track_id": "trk_aura_123",
    "listening_session_id": "sess_001",
    "played_at": "2026-04-02T18:40:00+02:00",
    "completion_percent": 92.4,
    "was_skipped": false,
    "source_context_type": "playlist",
    "source_context_id": "pl_123"
  }
}
```

### `listening_session`
Strategie :
- upsert par `entity_id`
- `started_at` est immuable
- `ended_at` et `total_listening_ms` peuvent etre completes ou allonges
- la version avec `ended_at` renseigne gagne sur une version ouverte

Payload exact :
```json
{
  "operation_id": "op_sess_001",
  "entity_type": "listening_session",
  "entity_id": "sess_001",
  "operation_type": "upsert",
  "device_id": "android_pixel_01",
  "occurred_at": "2026-04-02T18:42:39+02:00",
  "payload": {
    "started_at": "2026-04-02T18:00:00+02:00",
    "ended_at": "2026-04-02T18:40:00+02:00",
    "source_type": "playlist",
    "source_id": "pl_123",
    "network_type": "wifi",
    "total_listening_ms": 2400000
  }
}
```

### `playback_event`
Strategie :
- `playback_event` est additif
- deduplication par `entity_id`
- aucune edition destructive cote client

Payload exact :
```json
{
  "operation_id": "op_evt_001",
  "entity_type": "playback_event",
  "entity_id": "evt_001",
  "operation_type": "insert",
  "device_id": "android_pixel_01",
  "occurred_at": "2026-04-02T18:42:39+02:00",
  "payload": {
    "session_id": "sess_001",
    "track_id": "trk_aura_123",
    "event_type": "skip",
    "occurred_at": "2026-04-02T18:05:00+02:00",
    "position_start_ms": 0,
    "position_end_ms": 45000,
    "completion_percent": 22.1,
    "skip_reason": "next",
    "liked_during_playback": false
  }
}
```

### `user_track_stats`
Strategie :
- `user_track_stats` n'est pas la source de verite primaire
- la source de verite est `listening_session` + `playback_event` + `history_item`
- le client peut envoyer des agregats comme optimisation reseau
- le serveur peut les accepter comme hint, mais il reste autoritaire et peut recalculer
- si un agregat client entre en contradiction avec les evenements deja connus, le serveur recalcule et repond `merged`

Payload exact :
```json
{
  "operation_id": "op_stats_001",
  "entity_type": "user_track_stats",
  "entity_id": "trk_aura_123:day:2026-04-02",
  "operation_type": "upsert_hint",
  "device_id": "android_pixel_01",
  "occurred_at": "2026-04-02T18:42:39+02:00",
  "payload": {
    "track_id": "trk_aura_123",
    "period_type": "day",
    "period_start": "2026-04-02",
    "play_count": 3,
    "skip_count": 1,
    "complete_play_count": 2,
    "last_played_at": "2026-04-02T18:30:00+02:00",
    "total_listening_ms": 480000,
    "average_completion_percent": 81.4,
    "queue_add_count": 1,
    "playlist_add_count": 0,
    "is_liked": false
  }
}
```

### `download_job`
Strategie :
- le client peut demander la creation ou le retry d'un job
- le statut du job est ensuite server-authoritative
- aucune mutation sync directe cliente ne doit modifier `status`, `progress_percent`, `error_code` ou `error_message`

Payload autorise :
```json
{
  "operation_id": "op_job_retry_001",
  "entity_type": "download_job",
  "entity_id": "job_123",
  "operation_type": "retry_request",
  "device_id": "android_pixel_01",
  "occurred_at": "2026-04-02T18:42:39+02:00",
  "payload": {}
}
```

### `recent_search`
Strategie :
- `recent_searches` reste locale ou au mieux optionnelle
- aucune resolution de conflit canonique n'est definie pour cette entite

## Regles de deduplication
- Une operation deja vue via `operation_id` doit repondre `ignored_duplicate`.
- Une entite additive deja connue via `entity_id` doit etre ignoree sans erreur.
- Une suppression d'entite deja supprimee repond `ignored_duplicate`.

## Cas qui doivent retourner `conflict`
- suppression ou edition d'une playlist deja supprimee cote serveur
- reorder de playlist avec `base_order_token` stale
- mutation ciblee sur un `playlist_item_id` inconnu alors que la playlist a diverge
- mutation portant sur une entite server-authoritative qui ne doit pas etre modifiee par le client

## Reponse canonique de conflit
```json
{
  "operation_id": "op_pl_reorder_001",
  "entity_type": "playlist_reorder",
  "entity_id": "pl_123",
  "status": "conflict",
  "server_updated_at": "2026-04-02T16:43:10Z",
  "resolved_entity": {
    "playlist_id": "pl_123",
    "order_token": "ord_9b1caa"
  },
  "conflict": {
    "reason": "base_outdated",
    "server_entity": {
      "playlist_id": "pl_123",
      "order_token": "ord_9b1caa"
    },
    "client_payload": {
      "base_order_token": "ord_7ac4e2"
    },
    "retryable": true
  }
}
```

## Mapping recommande avec `sync_outbox`
- `sync_outbox.entity_type` reprend `entity_type`
- `sync_outbox.entity_id` reprend `entity_id`
- `sync_outbox.operation_type` reprend `operation_type`
- `sync_outbox.payload_json` stocke l'enveloppe canonique complete ou son `payload`
- `sync_outbox.status` reste local a l'app et ne fait pas partie de la sync cloud

## Hors perimetre
- `priority queue`
- navigation UI
- niveau de scroll
- donnees provider brutes non promues au modele AURA
