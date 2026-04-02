# Entites Metier

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
