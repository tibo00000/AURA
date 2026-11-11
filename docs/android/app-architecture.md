# Android App Architecture

## Couches
- `UI` : ecrans Compose, composants reutilisables, navigation.
- `Presentation` : ViewModels, `StateFlow`, intents utilisateur.
- `Domain` : cas d'usage player, recherche, playlists, bibliotheque, sync.
- `Data` : Room, MediaStore, adaptateurs reseau et persistance locale.

## Dependances majeures
- Media3/ExoPlayer pour la lecture.
- Room pour l'etat local structure.
- MediaStore pour decouvrir et lire les medias locaux presents sur l'appareil.
- FastAPI et Supabase pour les flux online et la sync optionnelle.

## Principes
- Un seul service player pilote la lecture.
- Le ViewModel n'encode pas la logique de queue, il delegue a un orchestrateur de playback.
- Les ecrans affichent des `UiState` deja prepares.
- La sync cloud ne doit pas bloquer l'usage local.

## Etat Android a documenter
- lecture courante
- file prioritaire
- contexte source
- historiques recents
- synchronisation utilisateur
- jobs de telechargement
- statistiques d'ecoute

## Etat exclu du modele de persistance metier
- pile d'ecrans de navigation
- niveau de scroll
- etats UI transitoires purement visuels

## Session réseau Android

Le client conserve la paire access/refresh dans le stockage chiffré existant.
L'expiration `exp` du JWT sert à déclencher un renouvellement côté client ; seule
la validation serveur autorise la requête. Les refresh sont sérialisés et liés à
une génération de session : logout ou connexion invalident les résultats anciens.
Une panne réseau ne supprime pas la session persistée. Les requêtes authentifiées
obtiennent un token courant avant envoi ; seules GET/HEAD peuvent être reprises
une fois après401, jamais les mutations non idempotentes. La reprise ne peut pas
changer de compte. Media3 renouvelle sur son thread de chargement, jamais sur le
Main Thread, et le polling ne réutilise pas indéfiniment un token capturé.

La paire tournée est enregistrée dans un unique enregistrement des mêmes
préférences chiffrées ; les anciennes clés sont lues pour compatibilité. Une
rotation commencée termine sa persistance malgré l'annulation de l'appelant,
avec un délai réseau borné. Il n'existe aucun repli vers un stockage en clair.
Si le Keystore est indisponible, les credentials existants ne sont pas effacés,
mais une récupération de l'accès au stockage ou une reconnexion sera nécessaire.
Le transport AURA refuse un autre origin et les redirections automatiques ;
Media3 obtient son bearer à chaque ouverture du flux, y compris après un401.
La frontière Media3 est définie dans `player/architecture.md` : connexion filtrée,
médias externes reconstruits depuis Room, bearer limité à l'endpoint streaming
privé HTTPS et aucune redirection suivie par le transport effectif. Cette garde
ne remplace ni le renouvellement de session ni les autorisations du backend.

SEC-004.1 ajoute une portée identité/génération sans credential autour du cache
streaming, distincte de la clé persistante stable par compte. Le refresh conserve
cette portée ; logout/login invalident les lecteurs privés et le buffer réseau
du service. Les fichiers favoris et Room ne sont pas modifiés. Le compte actif
peut relire son propre cache hors ligne, sans prétendre revalider RLS à distance.

## Code Mapping
- `android/app/src/main/java/com/aura/music/AuraApplication.kt` : point d'entree `Application`, initialise `AuraAppContainer`
- `android/app/src/main/java/com/aura/music/MainActivity.kt` : activite hote Compose, appelle `AuraApp()`
- `android/app/src/main/java/com/aura/music/core/AuraAppContainer.kt` : container DI manuel, fournit database, MediaStore et repository
- `android/app/src/main/java/com/aura/music/ui/AuraApp.kt` : shell applicatif Compose, navigation principale, mini-player shell, composants partages UI
- `android/app/src/main/java/com/aura/music/data/repository/LocalLibraryRepository.kt` : orchestration locale Room + MediaStore
