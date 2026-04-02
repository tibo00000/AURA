# Player States And Events

## Etats principaux
- `Idle`
- `Preparing`
- `Playing`
- `Paused`
- `Buffering`
- `Completed`
- `Error`

## Etat ecran attendu
- track courant
- metadonnees album et artiste
- progression et duree
- modes repeat et shuffle
- contenu de la priority queue
- information du contexte source
- message d'erreur eventuel

## Evenements utilisateur
- `Play`
- `Pause`
- `TogglePlayPause`
- `Next`
- `Previous`
- `SeekTo`
- `AddToQueue`
- `RemoveFromQueue`
- `ReorderQueue`
- `ToggleShuffle`
- `CycleRepeatMode`

## Evenements systeme
- `AudioFocusLost`
- `AudioFocusGained`
- `PlaybackEnded`
- `PlaybackFailed`
- `BecomingNoisy`
- `NetworkChanged`

## Transitions critiques
- `PlaybackEnded` doit demander au queue manager la prochaine piste.
- `PlaybackFailed` doit exposer une action de retry et permettre `next`.
- `RemoveFromQueue` sur la piste courante est refuse par le domaine.
