# Player Screen

## Role
Offrir le controle complet de la lecture en cours et rendre visible la relation entre piste courante, contexte source et priority queue.

## Reference de layout
- Voir `docs/android/screens/player-layout.md` pour le schema visuel de reference.

## Structure generale
- ecran plein hauteur
- zone hero haute pour artwork et metadonnees
- bloc de progression
- rangee de controles transport
- bloc d'actions secondaires
- partie basse reservee au contexte source et a la `priority queue`

## Zone hero
- grande cover centree
- titre de piste
- artiste
- album ou contexte secondaire
- toucher artiste ou album peut ouvrir les pages detail si cette interaction est retenue

## Progression
- slider de lecture
- temps ecoule a gauche
- duree totale a droite

## Controles transport
- `Shuffle`
- `Previous`
- `Play/Pause`
- `Next`
- `Repeat`

## Actions secondaires
- `Like`
- `Ajouter a une playlist`
- `...` si une couche d'actions supplementaires est necessaire

## Partie basse - contexte source
- carte ou section rappelant la source actuelle
- exemple : `Depuis la playlist X`
- si aucun contexte riche n'existe, afficher `Lecture directe`

## Partie basse - priority queue
- section dediee et visible sans changer d'ecran
- titre de section explicite
- liste verticale des pistes en attente
- `drag handle` visible
- action de suppression visible sur les pistes en attente
- la piste courante n'affiche pas d'action de suppression

## Queue row dans Player
- titre et artiste
- indicateur d'ordre
- `drag handle`
- action de suppression si la piste est en attente

## Etats
- preparation
- lecture
- pause
- buffering
- erreur

## Regles specifiques
- `Next` privilegie toujours la `priority queue`
- `Previous` suit la logique de redemarrage puis historique reel
- le reorder n'affecte jamais le contexte source
