# Queue Rules

## Regle centrale
Si une playlist joue et que l'utilisateur ajoute des titres a la file d'attente, ces titres sont places dans une `priority queue` distincte. Cette queue passe avant le reste de la playlist. Quand elle est vide, la lecture reprend la playlist au prochain element non encore lu.

## Ajout a la queue
- Un ajout manuel ne modifie pas l'ordre canonique de la playlist source.
- Plusieurs ajouts successifs s'empilent dans l'ordre d'ajout.
- Un doublon est autorise si l'utilisateur ajoute volontairement le meme titre plusieurs fois.

## Contexte de lecture (Source)
- Tout clic sur une piste depuis une liste (Home, Search, Playlist) doit fournir **l'integralite de la liste affichee** comme contexte de lecture (`contextTracks` et `startIndex`).
- Il est strictement interdit de creer un contexte `single_track` si la piste fait partie d'une liste visible a l'ecran. Cela garantit que les actions "Next" et "Previous" fonctionnent intuitivement pour l'utilisateur.

## Next
- Priorite a la `priority queue`.
- Sinon avance dans le contexte source.
- Sinon applique les regles de repetition ou passe en `idle`.

## Previous
- Si la piste courante a avance au-dela du seuil de redemarrage, `prev` relance cette piste.
- Sinon le player revient a la piste precedente reelle de l'historique.

## Remove
- Une piste en attente peut etre retiree de la queue.
- La piste courante ne peut pas etre retiree depuis l'ecran queue.
- Supprimer une piste de la queue n'affecte ni la playlist source ni l'historique.

## Reorder
- L'utilisateur peut reordonner la queue manuelle.
- Le reorder n'a aucun effet sur le contexte source.

## Code Mapping
- `android/app/src/main/java/com/aura/music/data/player/QueueManager.kt` : implementation des regles ci-dessus (next, prev, add, remove, reorder, shuffle, repeat)
- `android/app/src/main/java/com/aura/music/domain/player/PlaybackModels.kt` : modeles `QueuedTrack`, `PlaybackContext`, `TrackSource`, `RepeatMode`
