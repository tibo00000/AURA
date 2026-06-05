# Player Screen

## Role
Offrir le controle complet de la lecture en cours et rendre visible la relation entre piste courante, contexte source, priority queue et main queue.

## Reference de layout
- Voir `docs/android/screens/player-layout.md` pour le schema visuel de reference.

## Structure generale
- ecran plein hauteur
- TopBar avec fermeture, controle segmente `Lecteur` / `File` et menu contextuel
- vue `Lecteur` pour artwork, metadata, progression, transport et actions principales
- vue `File` pour queue scrollable et reorderable
- dialogue de selection de playlist quand l'utilisateur ajoute la piste courante a une playlist

## Vue Lecteur
- grande cover centree
- titre de piste
- artiste
- album si disponible
- slider de lecture
- temps ecoule et duree totale
- controles `Shuffle`, `Previous`, `Play/Pause`, `Next`, `Repeat`
- actions secondaires `Like` et `Ajouter a une playlist`
- carte de contexte source quand le contexte n'est pas `recent_tracks`
- bouton `Voir la file d'attente`

## Vue File
- `LazyColumn` pleine hauteur
- section `File d'attente prioritaire`
- section `A suivre`
- lignes avec cover, titre, artiste, suppression et drag handle
- reorganisation par appui long
- limite d'affichage de la main queue a 30 pistes visibles avec footer de resume si necessaire

## Menu contextuel
- ajouter ou retirer des favoris
- ajouter a une playlist
- voir l'artiste si disponible
- voir l'album si disponible

## Etats
- aucune lecture active
- lecture
- pause
- buffering
- erreur
- queue vide
- reorganisation active

## Regles specifiques
- `Next` privilegie toujours la `priority queue`
- `Previous` suit la logique de redemarrage puis historique reel
- le reorder de la main queue n'affecte jamais le contexte source
- les cles de queue reposent sur `internalId`
- le drag ne traverse jamais les frontieres entre priority queue et main queue
