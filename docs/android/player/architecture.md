# Player Architecture

## Objectif
Isoler la logique de lecture et de queue pour la rendre testable, persistante et moins sujette aux regressions.

## Blocs principaux
- `PlaybackService` : interface entre Media3, session Android et systeme.
- `PlaybackOrchestrator` : applique les regles `play`, `pause`, `next`, `prev`, `seek`.
- `QueueManager` : gere `playback context`, `priority queue`, `history`.
- `PlaybackStateStore` : persiste le minimum necessaire pour reprendre une session.
- `PlayerViewModel` : transforme l'etat metier en etat ecran.

## Responsabilites
- Media3 lit la source audio et expose les callbacks bruts.
- L'orchestrateur decide quelle piste doit etre jouee ensuite.
- Le queue manager gere reorder, remove, resume et priorites.
- Le state store sauvegarde contexte, piste courante, position et modes.

## Donnees persistantes minimales
- identifiant de la piste courante
- position courante
- contexte de lecture
- contenu de la priority queue
- modes shuffle et repeat

## Code Mapping
- `android/app/src/main/java/com/aura/music/service/PlaybackService.kt` : `MediaSessionService` Media3, initialise ExoPlayer et expose `MediaSession`
- `android/app/src/main/java/com/aura/music/domain/player/PlaybackOrchestrator.kt` : orchestre play, pause, next, prev, seek ; utilise `QueueManager` et `PlaybackStateStore`
- `android/app/src/main/java/com/aura/music/data/player/QueueManager.kt` : gere `playback context`, `priority queue` et `history` en memoire
- `android/app/src/main/java/com/aura/music/data/player/PlaybackStateStore.kt` : persiste le snapshot de reprise via `PlaybackSnapshotDao`
- `android/app/src/main/java/com/aura/music/ui/player/PlayerViewModel.kt` : transforme l'etat metier en etat ecran, met a jour la progression periodiquement
