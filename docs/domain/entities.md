# Entites Metier

## Strategie d'identite
- Tous les champs `id` manipules entre Android et le backend sont des chaines opaques au niveau du contrat d'API.
- Les identifiants locaux derives de `MediaStore` ou des slugs Android restent des identifiants internes au stockage `Room`.
- Les entites de catalogue online server-authoritative (`tracks`, `albums`, `artists`) recoivent des identifiants AURA emis par le backend et distincts des IDs locaux Android.
- Les identifiants provider (`providerTrackId`, `providerAlbumId`, `providerArtistId`) restent des references externes et ne remplacent jamais l'identifiant AURA.
- Les entites user-scoped synchronisables comme `Playlist`, `QueueItem`, `ListeningSession` ou `PlaybackEvent` peuvent conserver un identifiant texte opaque genere cote client ou cote serveur, a condition qu'il reste stable.
- La correspondance entre un contenu local deja present sur le telephone et un contenu online se fait par matching de metadonnees puis par persistance d'un mapping, jamais en supposant un partage naturel d'ID.

## Track
- `id`
- `title`
- `artistName`
- `albumTitle`
- `durationMs`
- `coverUri`
- `localAudioUri`
- `providerTrackId`
- `isDownloaded`
- `isLiked`
- `audioSourceType`

## Album
- `id`
- `title`
- `artistId`
- `coverUri`
- `releaseDate`
- `trackCount`
- `providerAlbumId`

## Artist
- `id`
- `name`
- `pictureUri`
- `providerArtistId`
- `summary`

## Playlist
- `id`
- `name`
- `coverUri`
- `trackIds`
- `createdAt`
- `updatedAt`
- `isPinned`

## PlaybackContext
- `id`
- `type` parmi `playlist`, `album`, `artist_mix`, `search_result`, `library`, `single_track`
- `sourceId`
- `orderedTrackIds`
- `currentIndex`
- `shuffleEnabled`
- `repeatMode`

## QueueItem
- `id`
- `trackId`
- `origin` parmi `priority_queue`, `playback_context`, `history`
- `position`
- `addedAt`

## DownloadJob
- `id`
- `trackId`
- `provider`
- `status`
- `progress`
- `errorCode`
- `createdAt`
- `updatedAt`

## UserProfile
- `id`
- `displayName`
- `avatarUri`
- `syncEnabled`
- `preferredSearchMode`
- `lastSeenAt`

## ListeningSession
- `id`
- `userId`
- `startedAt`
- `endedAt`
- `sourceType`
- `sourceId`
- `deviceType`
- `networkType`
- `totalListeningMs`

## PlaybackEvent
- `id`
- `sessionId`
- `trackId`
- `eventType`
- `occurredAt`
- `positionStartMs`
- `positionEndMs`
- `completionPercent`
- `skipReason`
- `likedDuringPlayback`

## HistoryItem
- `id`
- `trackId`
- `playedAt`
- `completionPercent`
- `wasSkipped`
- `sourceContextType`
- `sourceContextId`

## TrackLike
- `trackId`
- `likedAt`
- `sourceContextType`
- `sourceContextId`

## UserTrackStats
- `userId`
- `trackId`
- `periodType`
- `periodStart`
- `playCount`
- `skipCount`
- `completePlayCount`
- `lastPlayedAt`
- `totalListeningMs`
- `averageCompletionPercent`
- `isLiked`

## ProviderTrack
- `provider`
- `providerTrackId`
- `title`
- `artistName`
- `albumTitle`
- `durationMs`
- `artworkUrl`
- `previewUrl`
- `streamCapability`
