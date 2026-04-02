# Playback Model

## Objectif
Definir un modele de lecture robuste, explicite et testable afin d'eviter les bugs observes dans le prototype.

## Trois structures distinctes
- `playback context` : source de lecture principale, par exemple une playlist ou un album.
- `priority queue` : ajouts manuels que l'utilisateur veut entendre avant le reste du contexte.
- `history` : pile ordonnee des pistes deja jouees ou sautees en arriere.

## Regle canonique
- Quand un contexte source joue et que l'utilisateur ajoute une piste a la queue, cette piste entre dans la `priority queue`.
- La `priority queue` est lue avant les pistes restantes du contexte source.
- Quand la `priority queue` est vide, la lecture reprend au prochain element non encore lu du contexte source.

## Invariants
- La piste courante n'appartient qu'a une seule position logique a la fois.
- `next` privilegie toujours la `priority queue` si elle contient au moins un element.
- `prev` consulte l'historique reel de lecture avant de remonter dans le contexte source.
- La suppression d'une piste dans la queue ne doit jamais decaler la definition du contexte source.
- Le reorder ne concerne que la `priority queue`, jamais l'ordre canonique d'une playlist source.

## Modes
- `shuffle` reordonne le contexte source, pas la `priority queue` deja construite.
- `repeat one` repete la piste courante.
- `repeat all` repete le contexte source une fois la `priority queue` vide.
