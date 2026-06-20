# Settings Screen

## Role
Permettre la configuration de l'application sans complexifier les ecrans media.

## Structure generale
- liste verticale de sections
- chaque section est presentee comme un groupe de preferences

## Sections
- `Compte et sync`
- `Stockage local`
- `Recherche`
- `Qualite audio`
- `Diagnostics`

## Compte et sync
- état de connexion
- bouton "Gérer le stockage cloud" redirigeant vers l'écran dédié Stockage Cloud (CloudSyncScreen)


## Stockage local
- informations sur le cache
- action de nettoyage si necessaire

## Recherche
- autorisation ou non de la recherche online
- politique reseau type `Wi-Fi uniquement` ou `Tout reseau`

## Portee canonique des reglages reseau
- les reglages de cette page sont autoritatifs et non indicatifs
- `Recherche online` est le master switch de tous les appels backend initiees par Android pour :
  - les resultats online dans `Search`
  - la resolution d'une entite locale vers un ID backend
  - l'enrichissement d'image ou de metadonnees pour `Search`, `Artist` et `Album`
  - le chargement detail online d'un artiste ou d'un album
- si `Recherche online` est desactivee, Android ne doit lancer aucun appel backend de recherche ou d'enrichissement media
- `Politique reseau` gouverne tous les appels precedents selon la connectivite courante

## Valeurs de politique reseau
- `disabled` : aucun acces reseau pour la famille de fonctionnalites concernee
- `wifi_only` : acces autorise uniquement sur reseau non metered assimile a du Wi-Fi
- `any_network` : acces autorise sur tout reseau disponible

## Regles de comportement
- les suggestions locales pendant la saisie restent 100% locales et ne doivent jamais declencher de reseau
- une recherche locale complete peut lancer un enrichissement d'image en arriere-plan uniquement si les reglages l'autorisent
- les ecrans `Artist` et `Album` peuvent enrichir leurs metadonnees ou leur hero uniquement si les reglages l'autorisent
- si la politique reseau bloque l'appel, l'application affiche un placeholder local sans erreur bloquante

## Qualite audio
- section reservee aux futures options si necessaire

## Diagnostics
- acces aux informations techniques utiles
- acces possible aux journaux ou informations de version si exposees

## Etats
- local sans compte
- connecte avec sync active
- options futures desactivees mais documentees proprement
