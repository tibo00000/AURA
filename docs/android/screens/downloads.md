# Downloads Screen

## Role
Suivre l'etat des traitements lies a la disponibilite locale des pistes.

## Reference de layout
- Voir `docs/android/screens/downloads-layout.md` pour le schema visuel de reference.

## Structure generale
- header avec titre `Telechargements`
- bouton de rafraichissement manuel dans la TopBar (`Icons.Rounded.Refresh`)
- sous-navigation par filtres
- liste verticale reactive de jobs
- dialogue de choix de version quand un job demande une resolution utilisateur

## Filtres
- `En cours`
- `Termines`
- `Erreurs`
- `En cours` regroupe les jobs `queued`, `requires_resolution` et `running`
- `Termines` regroupe les jobs `succeeded`
- `Erreurs` regroupe les jobs `failed` et `cancelled`

## Ligne de download
- titre principal
- artiste en metadonnee secondaire
- cover ou placeholder
- etat ou progression
- action contextuelle a droite

## Actions
- `Reessayer` pour `failed` et `cancelled`
- `Choisir` pour `requires_resolution`
- `Lire` quand le contenu est disponible
- rafraichir manuellement les jobs depuis la TopBar

## Etats
- liste vide par filtre
- progression en temps reel
- erreur par job
- succes avec acces direct a la piste
- choix de version YTM requis
- erreur reseau non bloquante
- stockage des fichiers physiques sous `context.filesDir/downloads/{trackId}.mp3`
