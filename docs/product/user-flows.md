# User Flows Produit

## Format canonique
Chaque user flow documente :
- un objectif utilisateur
- des preconditions
- un point d'entree
- la sequence ecran par ecran
- les boutons ou actions exactes
- les etats de chargement et d'erreur
- le resultat attendu
- les postconditions

## Perimetre de ce lot
Ce document detaille les parcours de recherche, de lecture, de queue et de playlists. Le flow complet de telechargement reste hors de ce lot tant que la source audio finale n'est pas figee.

## Recherche globale dans Search

### Objectif
Permettre a l'utilisateur de saisir une requete unique et d'obtenir les resultats les plus pertinents, qu'ils proviennent de la bibliotheque locale ou d'une source online.

### Preconditions
- L'utilisateur est sur l'application AURA.
- L'index local est disponible.
- Le reseau peut etre autorise ou non selon les reglages et la connectivite.

### Point d'entree
- Onglet `Search` depuis la navigation principale.

### Sequence
1. L'utilisateur ouvre `Search`.
2. L'utilisateur appuie sur la barre `Rechercher...`.
3. L'utilisateur saisit une requete.
4. Tant que la requete contient moins de trois caracteres, l'application n'affiche pas encore de suggestions detaillees.
5. A partir de trois caracteres, l'application affiche des suggestions locales uniquement.
6. L'utilisateur valide la recherche ou touche une suggestion locale.
7. L'application lance la recherche locale complete.
8. Si le reseau est autorise et disponible, l'application lance aussi la recherche online complete.
9. L'ecran affiche un bloc `Meilleur resultat`.
10. L'ecran affiche ensuite `Dans votre bibliotheque` si des correspondances locales existent.
11. L'ecran affiche ensuite les sections `En ligne - Titres`, `En ligne - Artistes` et `En ligne - Albums` si elles sont disponibles.

### Boutons et actions
- toucher la barre de recherche
- saisir du texte
- toucher `X` pour vider la requete
- toucher une ligne de piste pour lancer la lecture
- toucher `...` pour ouvrir le menu contextuel
- toucher un resultat artiste pour ouvrir la page artiste
- toucher un resultat album pour ouvrir la page album

### Etats
- Etat vide avant saisie.
- Etat de suggestions locales pendant la saisie.
- Etat de chargement pendant la recherche complete.
- Etat mixte avec local et online.
- Etat local uniquement si le reseau n'est pas autorise ou indisponible.
- Etat d'erreur online non bloquant si le provider echoue.

### Resultat attendu
- L'utilisateur voit en haut le resultat juge le plus pertinent.
- Si une piste locale correspond fortement, elle est privilegiee dans `Meilleur resultat`.
- Les resultats locaux apparaissent avant les sections online lorsqu'ils sont pertinents.

### Postconditions
- La requete courante reste visible tant que l'utilisateur ne la vide pas.
- L'utilisateur peut lancer une lecture, ouvrir un artiste, ouvrir un album ou ouvrir le menu contextuel d'une piste.

## Recherche dans Search sans reseau autorise

### Objectif
Permettre une recherche utile meme sans reseau ou quand la recherche online est desactivee.

### Preconditions
- L'utilisateur est sur `Search`.
- Le reseau est indisponible ou non autorise par les reglages.

### Point d'entree
- Onglet `Search`.

### Sequence
1. L'utilisateur saisit une requete.
2. L'application interroge l'index local.
3. L'ecran affiche `Meilleur resultat` si un resultat local est pertinent.
4. L'ecran affiche `Dans votre bibliotheque`.
5. Les sections online ne sont pas affichees.

### Boutons et actions
- saisir du texte
- vider la requete
- toucher une piste locale pour lancer la lecture
- toucher `...` sur une piste locale

### Etats
- local uniquement
- aucun resultat local

### Resultat attendu
- L'utilisateur peut continuer a chercher et a lire des contenus locaux sans rupture d'experience.

### Postconditions
- Aucune tentative online ne doit perturber l'experience locale.

## Ouvrir le menu contextuel d'une TrackRow

### Objectif
Permettre a l'utilisateur d'acceder aux actions secondaires d'une piste depuis une ligne de resultat, de bibliotheque, de playlist ou de queue.

### Preconditions
- Une `TrackRow` est visible.
- La piste possede au minimum un identifiant metier.

### Point d'entree
- Bouton `...` sur une `TrackRow`.

### Sequence
1. L'utilisateur appuie sur `...`.
2. L'application ouvre le menu contextuel.
3. L'utilisateur choisit une action disponible pour cette piste.

### Actions possibles
- `Lire maintenant`
- `Ajouter a la file d'attente`
- `Ajouter a une playlist`
- `Voir l'artiste`
- `Voir l'album`
- `Telecharger` uniquement si la piste n'est pas disponible localement
- `Supprimer le telechargement` uniquement si la piste est deja disponible localement
- `Supprimer` uniquement dans les contextes ou cela a un sens metier

### Etats
- menu complet si toutes les metadonnees sont disponibles
- menu degrade si l'artiste ou l'album ne peuvent pas etre resolus

### Resultat attendu
- Le menu ne presente que des actions valides pour l'etat reel de la piste.

### Postconditions
- Si l'utilisateur choisit `Voir l'artiste`, l'application ouvre la page artiste.
- Si l'utilisateur choisit `Voir l'album`, l'application ouvre la page album.
- Si l'utilisateur choisit `Ajouter a la file d'attente`, la piste rejoint la `priority queue`.

## Ouvrir la page artiste depuis une TrackRow

### Objectif
Permettre a l'utilisateur de naviguer vers l'artiste associe a une piste.

### Preconditions
- La piste possede une reference artiste exploitable.

### Point d'entree
- Menu `...` puis action `Voir l'artiste`.

### Sequence
1. L'utilisateur ouvre le menu `...`.
2. L'utilisateur appuie sur `Voir l'artiste`.
3. L'application resout l'identifiant artiste.
4. L'application ouvre `Artist`.
5. L'ecran charge les informations locales puis les enrichissements online si disponibles.

### Resultat attendu
- La page artiste s'ouvre sur le bon artiste, sans lancer la lecture automatiquement.

## Ouvrir la page album depuis une TrackRow

### Objectif
Permettre a l'utilisateur de naviguer vers l'album associe a une piste.

### Preconditions
- La piste possede une reference album exploitable.

### Point d'entree
- Menu `...` puis action `Voir l'album`.

### Sequence
1. L'utilisateur ouvre le menu `...`.
2. L'utilisateur appuie sur `Voir l'album`.
3. L'application resout l'identifiant album.
4. L'application ouvre `Album`.
5. L'ecran charge la liste ordonnee des pistes et les metadonnees de l'album.

### Resultat attendu
- La page album s'ouvre sur le bon album, sans modifier la lecture en cours.

## Lecture depuis une playlist

### Objectif
Lancer la lecture d'une playlist comme contexte source principal.

### Preconditions
- Une playlist existe.
- La playlist contient au moins une piste lisible.

### Point d'entree
- Ecran `Playlist Detail`.

### Sequence
1. L'utilisateur ouvre une playlist depuis `Playlists` ou `Library`.
2. L'utilisateur appuie sur `Play` ou sur une piste.
3. L'application cree un `playback context` de type playlist.
4. Si l'utilisateur a touche `Play`, la lecture commence a la premiere piste du contexte courant.
5. Si l'utilisateur a touche une piste, la lecture commence a cette position dans le contexte.
6. La `priority queue` reste vide tant qu'aucun ajout manuel n'est fait.
7. Le mini-player apparait.
8. L'utilisateur peut ouvrir `Player` pour voir la queue et le contexte source.

### Boutons et actions
- `Play`
- touche sur une `TrackRow`
- mini-player

### Etats
- chargement du contexte
- lecture en cours
- erreur si aucune piste lisible n'est disponible

### Resultat attendu
- La lecture demarre depuis la playlist selectionnee et la playlist devient le contexte source actif.

### Postconditions
- `next` et `prev` utilisent ce contexte tant qu'il n'est pas remplace.

## Ajouter une piste a la priority queue pendant la lecture d'une playlist

### Objectif
Permettre a l'utilisateur d'inserer une piste a jouer avant la suite de la playlist sans modifier l'ordre de la playlist.

### Preconditions
- Une playlist est deja en cours de lecture comme `playback context`.
- Une piste cible est visible dans `Search`, `Library`, `Artist`, `Album` ou une playlist.

### Point d'entree
- Action `Ajouter a la file d'attente` depuis une ligne de piste ou un menu `...`.

### Sequence
1. L'utilisateur appuie sur `Ajouter a la file d'attente`.
2. L'application ajoute la piste en fin de `priority queue`.
3. La piste courante continue normalement.
4. Au prochain `next` ou a la fin de la piste courante, l'application lit la premiere piste de la `priority queue`.
5. Une fois la `priority queue` vide, la lecture reprend la playlist au prochain element non encore lu.

### Etats
- confirmation de l'ajout
- file prioritaire non vide dans `Player`

### Resultat attendu
- La piste ajoutee passe avant la suite de la playlist.
- L'ordre canonique de la playlist n'est pas modifie.

### Postconditions
- La `priority queue` contient la piste ajoutee jusqu'a sa lecture ou sa suppression.

## Utiliser Next pendant qu'une priority queue existe

### Objectif
Permettre a l'utilisateur de passer a la piste suivante en respectant la priorite de la queue manuelle.

### Preconditions
- Une lecture est active.
- La `priority queue` peut etre vide ou non.

### Point d'entree
- Bouton `Next` du mini-player ou du player plein ecran.

### Sequence
1. L'utilisateur appuie sur `Next`.
2. L'application interroge d'abord la `priority queue`.
3. Si la `priority queue` contient une piste en attente, cette piste devient la piste courante.
4. Sinon, l'application avance dans le `playback context`.
5. Si plus aucune piste n'est disponible, le player applique les regles de repetition ou passe en `Idle`.

### Resultat attendu
- `Next` privilegie toujours la `priority queue`.

## Utiliser Previous pendant la lecture

### Objectif
Permettre a l'utilisateur de revenir logiquement en arriere sans casser l'historique reel de lecture.

### Preconditions
- Une lecture est active.

### Point d'entree
- Bouton `Previous` du mini-player ou du player plein ecran.

### Sequence
1. L'utilisateur appuie sur `Previous`.
2. L'application compare la progression courante au seuil de redemarrage.
3. Si ce seuil est depasse, la piste courante redemarre.
4. Sinon, l'application consulte l'historique reel.
5. Si un element precedent existe, il devient la piste courante.
6. Sinon, la piste courante redemarre.

### Resultat attendu
- `Previous` suit la logique standard enrichie definie pour AURA.

## Retirer une piste de la priority queue

### Objectif
Permettre a l'utilisateur de nettoyer la queue manuelle avant lecture.

### Preconditions
- La `priority queue` contient au moins une piste en attente.

### Point d'entree
- Ecran `Player`, section queue.

### Sequence
1. L'utilisateur ouvre `Player`.
2. L'utilisateur repere une piste en attente dans la `priority queue`.
3. L'utilisateur appuie sur l'action de suppression de cette ligne.
4. L'application retire la piste de la `priority queue`.
5. L'ecran met a jour l'ordre restant.

### Resultat attendu
- La piste retiree ne sera plus lue via la `priority queue`.
- Le `playback context` n'est pas modifie.

### Postconditions
- La piste courante reste intacte.

## Reordonner la priority queue

### Objectif
Permettre a l'utilisateur de changer l'ordre de lecture des pistes ajoutees manuellement.

### Preconditions
- La `priority queue` contient au moins deux pistes en attente.

### Point d'entree
- Ecran `Player`, section queue.

### Sequence
1. L'utilisateur ouvre `Player`.
2. L'utilisateur saisit le `drag handle` d'une piste en attente.
3. L'utilisateur la deplace dans la `priority queue`.
4. L'application persiste le nouvel ordre.

### Resultat attendu
- Les prochaines lectures issues de la `priority queue` suivent le nouvel ordre.
- Le `playback context` ne change pas.

## Creer une playlist

### Objectif
Permettre a l'utilisateur de creer une playlist vide.

### Preconditions
- L'utilisateur a acces a `Playlists`.

### Point d'entree
- Ecran `Playlist List`.

### Sequence
1. L'utilisateur appuie sur `Creer une playlist`.
2. L'application ouvre un formulaire ou une boite de dialogue.
3. L'utilisateur saisit le nom de la playlist.
4. L'utilisateur valide.
5. L'application cree la playlist et ouvre son detail, ou met a jour la liste selon le design retenu.

### Resultat attendu
- Une nouvelle playlist existe avec un identifiant stable et un nom utilisateur.

## Ajouter une piste a une playlist

### Objectif
Permettre a l'utilisateur d'enrichir une playlist depuis n'importe quelle ligne de piste.

### Preconditions
- Au moins une playlist existe ou peut etre creee au moment de l'action.
- Une `TrackRow` est visible.

### Point d'entree
- Menu `...` puis `Ajouter a une playlist`.

### Sequence
1. L'utilisateur ouvre le menu `...` d'une piste.
2. L'utilisateur choisit `Ajouter a une playlist`.
3. L'application ouvre un selecteur de playlists.
4. L'utilisateur choisit une playlist existante ou cree une nouvelle playlist si ce parcours est disponible.
5. L'application ajoute la piste a la playlist cible.

### Resultat attendu
- La piste apparait dans la playlist cible.
- La lecture en cours n'est pas modifiee.

## Retirer une piste d'une playlist

### Objectif
Permettre a l'utilisateur de maintenir le contenu exact d'une playlist.

### Preconditions
- Une playlist existe.
- La piste est deja presente dans cette playlist.

### Point d'entree
- Ecran `Playlist Detail`, menu `...` de la piste ou action de suppression dediee.

### Sequence
1. L'utilisateur ouvre le detail de la playlist.
2. L'utilisateur ouvre l'action de suppression sur la piste cible.
3. L'application demande confirmation si le design le requiert.
4. L'application retire la piste de la playlist.

### Resultat attendu
- La piste n'apparait plus dans la playlist.
- Si la playlist sert de contexte de lecture actif, son ordre source est mis a jour sans casser la piste courante.

## Renommer une playlist

### Objectif
Permettre a l'utilisateur de corriger ou faire evoluer le nom d'une playlist.

### Preconditions
- Une playlist existe.

### Point d'entree
- Ecran `Playlist Detail`, bouton `Renommer`.

### Sequence
1. L'utilisateur appuie sur `Renommer`.
2. L'application ouvre un champ ou une boite de dialogue pre-remplie.
3. L'utilisateur modifie le nom.
4. L'utilisateur valide.
5. L'application met a jour le nom dans la liste et dans le detail.

### Resultat attendu
- La playlist conserve son identifiant et son contenu, seul son nom change.

## Supprimer une playlist

### Objectif
Permettre a l'utilisateur de retirer une playlist devenue inutile.

### Preconditions
- Une playlist existe.

### Point d'entree
- Ecran `Playlist Detail`, bouton `Supprimer`.

### Sequence
1. L'utilisateur appuie sur `Supprimer`.
2. L'application ouvre une confirmation.
3. L'utilisateur confirme.
4. L'application supprime la playlist.
5. L'application revient a `Playlist List` ou a l'ecran precedent.

### Etats
- confirmation obligatoire
- erreur de suppression si la sync echoue, sans reafficher une playlist incoherente

### Resultat attendu
- La playlist disparait de la liste.
- La suppression d'une playlist n'efface pas les pistes elles-memes de la bibliotheque.
