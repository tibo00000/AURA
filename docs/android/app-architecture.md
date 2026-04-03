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

## Code Mapping
- `android/app/src/main/java/com/aura/music/AuraApplication.kt` : point d'entree `Application`, initialise `AuraAppContainer`
- `android/app/src/main/java/com/aura/music/MainActivity.kt` : activite hote Compose, appelle `AuraApp()`
- `android/app/src/main/java/com/aura/music/core/AuraAppContainer.kt` : container DI manuel, fournit database, MediaStore et repository
- `android/app/src/main/java/com/aura/music/ui/AuraApp.kt` : shell applicatif Compose, navigation principale, mini-player shell, composants partages UI
- `android/app/src/main/java/com/aura/music/data/repository/LocalLibraryRepository.kt` : orchestration locale Room + MediaStore
