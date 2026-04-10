# Search Screen

## Role
Permettre une recherche unique orientee intention utilisateur, avec evaluation conjointe des resultats locaux et online si le reseau est autorise.

## Source online
- Les resultats online v1 proviennent exclusivement du backend AURA.
- L'application Android ne doit pas appeler Deezer directement.
- Le backend mappe les resultats provider vers le modele AURA avant retour client.

## Structure generale
- ecran en scroll vertical unique
- barre de recherche sticky en tete
- zone de suggestions locales sous la barre pendant la saisie
- bloc `Meilleur resultat`
- bandeau `Dans votre bibliotheque`
- sections online sous le bloc local

## Comportement de saisie
- aucune proposition avant trois caracteres saisis
- a partir de trois caracteres, l'application affiche des suggestions locales uniquement
- les suggestions sont basees sur les donnees locales deja connues par l'application
- la validation clavier ou l'action de recherche lance l'affichage complet des resultats
- si le reseau est autorise et disponible, la recherche complete interroge le local et le backend online en parallele

## Barre de recherche
- largeur complete avec forme pilule
- icone loupe a gauche
- texte `Rechercher...` quand le champ est vide
- bouton `X` a droite uniquement quand la requete n'est pas vide
- le focus ouvre le clavier et reserve un espace aux suggestions locales

## Suggestions locales
- liste verticale compacte juste sous la barre de recherche
- limitee a des correspondances locales rapides
- chaque suggestion affiche type, titre principal et metadonnee secondaire
- toucher une suggestion remplit la requete et lance la recherche complete
- pas de suggestions online a ce stade

## Bloc Meilleur resultat
- grande carte hero occupant toute la largeur utile
- hauteur visuellement marquee pour dominer le haut de page
- contenu adapte au type retenu

## Meilleur resultat - piste
- grande cover a gauche ou en fond selon le design final
- titre, artiste, album
- bouton `Play` visible
- bouton `Like` visible
- bouton `...` visible
- toucher la carte hors boutons lance la lecture

## Meilleur resultat - artiste
- visuel artiste large et dominant
- nom artiste tres visible
- bouton d'ouverture de la page artiste
- bouton secondaire `Lancer un mix` reserve si cette fonction est disponible

## Meilleur resultat - album
- grande cover album
- titre album, artiste, nombre de pistes si connu
- bouton d'ouverture de la page album
- bouton `Play`

## Dans votre bibliotheque
- section locale toujours prioritaire quand des resultats pertinents existent
- en-tete avec titre de section
- sous-navigation par onglets

## Onglets locaux
- `Titres`
- `Albums`
- `Artistes`
- `Playlists`

## Contenu de l'onglet local
- `Titres` : liste verticale de `TrackRow`
- `Albums` : rail horizontal de cartes album
- `Artistes` : rail horizontal de cartes artiste
- `Playlists` : rail horizontal de cartes playlist

## Resultats locaux - pistes
- `TrackRow` classique
- bouton `Like` visible directement
- les autres actions passent par `...`
- toucher la ligne lance la lecture

## Resultats locaux - albums et artistes
- pas de rows textuelles simples
- cartes carrees ou quasi carrees
- largeur equivalente a une demi-row environ
- hauteur equivalente a deux rows environ
- scroll horizontal
- artiste : image dominante + nom
- album : cover dominante + titre + artiste

## Sections online
- affichees uniquement si le reseau est autorise et disponible
- ordre :
  - `En ligne - Titres`
  - `En ligne - Artistes`
  - `En ligne - Albums`

## Resultats online - titres
- liste verticale de pistes ou petit rail horizontal selon le volume de resultats
- `Like` non prioritaire si la piste n'est pas encore locale
- `...` visible pour les actions secondaires
- toucher la ligne ne doit pas lancer un telechargement implicite

## Resultats online - artistes et albums
- cartes visuelles, pas de rows
- meme famille visuelle que les cartes locales
- scroll horizontal
- toucher la carte ouvre la page detail

## Menu contextuel d'une piste dans Search
- `Lire maintenant`
- `Ajouter a la file d'attente`
- `Ajouter a une playlist`
- `Voir l'artiste`
- `Voir l'album`
- `Telecharger` uniquement si la piste n'est pas disponible localement
- `Supprimer le telechargement` uniquement si la piste est deja disponible localement

## Etats
- vide avant saisie
- suggestions locales uniquement pendant la saisie
- resultats complets apres validation
- local uniquement si le reseau n'est pas autorise ou indisponible
- erreur online non bloquante si le provider echoue
- aucun resultat si ni local ni online ne correspondent

## Regles
- `Search` ne distingue pas explicitement recherche locale et recherche online dans l'intention utilisateur
- l'application privilegie local dans `Meilleur resultat` si la correspondance est forte
- `Library` reste la surface de possession locale, `Search` reste la surface de recherche globale
