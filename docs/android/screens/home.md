# Home Screen

## Role
Point d'entree principal de l'application, centre sur la reprise d'ecoute et les acces rapides.

## Structure generale
- scroll vertical unique
- header haut avec salutation ou titre contextuel
- bloc de reprise d'ecoute
- rail de playlists recentes
- rail de mixes et recommandations
- acces rapide aux downloads

## Header
- titre de contexte type `Bonsoir` ou `Accueil`
- sous-texte facultatif rappelant la derniere activite
- action de navigation rapide vers `Settings` si un acces global est retenu

## Bloc reprise d'ecoute
- carte hero large
- affiche la piste ou la playlist la plus recente
- bouton `Reprendre`
- bouton `...`
- toucher la carte ouvre `Player` si une lecture resumable existe

## Playlists recentes
- en-tete de section
- rail horizontal de cartes playlist
- chaque carte montre cover, nom et compteur approximatif de pistes
- toucher une carte ouvre `Playlist Detail`

## Mixes et recommandations
- cartes de categorie a fort impact visuel
- scroll horizontal
- peut contenir des mixes locaux ou des recommandations derivees plus tard
- si la recommandation online n'est pas disponible, la section peut etre masquee sans casser l'ecran

## Acces rapide aux downloads
- carte ou ligne dediee
- affiche un compteur de jobs en cours ou en erreur si disponible
- touche ouvre `Downloads`

## Boutons et actions
- `Reprendre`
- toucher une carte playlist
- toucher une carte de mix
- ouvrir `Player`
- ouvrir `Downloads`

## Etats
- vide si aucune bibliotheque n'est disponible
- reprise seule si aucune recommandation n'est prete
- erreur online non bloquante pour les suggestions enrichies
