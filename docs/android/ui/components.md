# Composants UI Reutilisables

## Objectif
Les composants ci-dessous constituent la base Compose d'AURA. Tout composant expose un `Modifier`.

## MiniPlayer
- Role : lecteur persistant au-dessus de la navigation basse.
- Contenu : pochette, titre, artiste, boutons `previous`, `play/pause`, `next`.
- Action : ouvre `PlayerScreen` au clic.
- Etat : gere `loading`, `playing`, `paused`, `error`.

## TrackRow
- Role : affichage standard d'une piste dans la bibliotheque, les playlists et la queue.
- Gauche : pochette, titre, artiste.
- Droite : `like`, `add to queue`, menu contextuel.
- Variantes : standard, compact, currently playing.
- Menu contextuel canonique :
  - `Lire maintenant`
  - `Ajouter a la file d'attente`
  - `Ajouter a une playlist`
  - `Voir l'artiste`
  - `Voir l'album`
  - `Telecharger` uniquement si la piste n'est pas disponible localement
  - `Supprimer le telechargement` uniquement si la piste est deja disponible localement
  - `Supprimer` uniquement dans les contextes ou la suppression a un sens metier

## QueueRow
- Role : ligne dediee a la `priority queue`.
- Actions : drag handle, supprimer, ouvrir le menu contextuel.
- Contrainte : la piste courante ne montre pas l'action supprimer.

## ActionButton
- Role : bouton primaire pour creer une playlist, lancer un mix ou demarrer une action de fond.
- Style : pilule, fond uni ou gradient.

## BottomNavigationBar
- Role : navigation `Home`, `Search`, `Library`.
- Etat actif : texte et icone orange.

## SectionHeader
- Role : separer les zones d'un scroll.
- Variantes : standard, accent orange pour resultats de recherche.

## CategoryCard
- Role : representer mixes, entrees de bibliotheque ou raccourcis.
- Visuel : fond gradient, icone ou cover marquee.

## SearchBar
- Role : saisie uniforme sur toutes les pages de recherche.
- Placeholder : `Rechercher...`
- Etats : vide, saisie, loading, clear.

## EmptyStateCard
- Role : etat vide descriptif pour playlists, downloads et recherche online hors ligne.
- Contenu : titre, message, action primaire facultative.
