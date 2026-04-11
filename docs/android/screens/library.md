# Library Screen

## Role
Centraliser la collection personnelle locale et les points d'entree de gestion.

## Structure generale
- ecran a scroll vertical
- header avec titre `Library`
- barre de recherche locale dediee
- grille ou liste d'entrees principales
- acces secondaire vers downloads

## Barre de recherche locale
- recherche orientee possession locale
- porte uniquement sur les donnees presentes localement ou deja synchronisees
- ne declenche pas de recherche online

## Entrees principales
- `Titres`
- `Albums`
- `Artistes`
- `Playlists`

## Presentation
- cartes ou lignes larges selon le design final
- chaque entree affiche un libelle, une icone et un sous-texte de contexte si disponible
- toucher une entree ouvre la surface correspondante

## Zones secondaires
- acces a `Downloads`
- eventuelle zone compte et sync si cette information est importante au niveau bibliotheque

## Boutons et actions
- saisir une recherche locale
- ouvrir une entree principale
- ouvrir `Downloads`

## Etats
- vide si aucune bibliotheque locale n'existe
- charge si des medias sont indexes
- sync optionnelle si un compte est connecte

## Donnees
- Room pour l'etat applicatif
- MediaStore pour la presence des fichiers locaux
- metadonnees cloud optionnelles
