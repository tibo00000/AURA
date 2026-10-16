# ADR 007 - Compose Multiplatform Desktop and Web Client

## Statut
Accepte

## Contexte
AURA doit étendre ses capacités en proposant un client Bureau (Windows/macOS/Linux) et un client Web. Le profil des utilisateurs comprend des joueurs de jeux vidéo exigeants, ce qui impose des contraintes strictes sur la consommation de ressources en arrière-plan (CPU/GPU/RAM) pour éviter toute baisse de framerate ou saccade audio. De plus, pour des raisons de maintenance, le projet vise une réutilisation maximale du code UI et de la logique métier.

## Decision
- Le développement des clients Bureau et Web repose sur **Compose Multiplatform** (CMP).
- Le client Bureau cible **Kotlin/JVM** et le client Web cible **Kotlin/Wasm**.
- L'interface utilisateur (Compose) et la logique métier sont communes à Android, au Bureau et au Web.
- La persistance locale s'appuie sur **Room Multiplatform** pour partager directement les schémas existants de la base locale.
- **Cible Java Hybride** : La compilation du code commun cible JDK 17 pour des raisons de compatibilité d'écosystème. L'application Bureau est packagée et exécutée avec un environnement d'exécution **JDK 21** personnalisé.
- **Optimisations de la JVM** : L'environnement JDK 21 est configuré pour activer le ramasse-miettes générationnel à très faible latence **Generational ZGC** (`-XX:+UseZGC`) et exploiter les threads virtuels (Project Loom) pour les E/S asynchrones.
- **Réduction au System Tray et Barre des tâches** : L'application Bureau supporte la minimisation standard dans la barre des tâches (bouton `-`) pour un accès rapide. Le bouton de fermeture (`X`) réduit l'application dans la zone de notification (System Tray) en tâche de fond, suspendant totalement le rendu graphique de l'interface (canevas Skia) pour ramener la consommation CPU/GPU à un niveau proche de zéro.

## Consequences
- Mutualisation complète de l'interface utilisateur entre Android, le Bureau et le Web.
- Élimination de la surcharge mémoire liée aux navigateurs intégrés (type Electron) au profit de Skia (rendu accéléré matériellement) et d'un JRE modulaire.
- Garantie de pauses de ramasse-miettes sub-millisecondes grâce à Generational ZGC pour préserver les performances en jeu.
- Création de la section documentaire `docs/desktop/` pour détailler l'architecture.
