# Desktop & Web App Architecture (Compose Multiplatform)

## Couches
- `UI` : Écrans Compose partagés (`commonMain`), composants réutilisables, styles de Thème (Outfit, Blaze Orange), et intégration de la zone de notification Bureau (`jvmMain`).
- `Presentation` : ViewModels partagés, gestion des flux d'états réactifs (`StateFlow`) et interactions utilisateur unifiées.
- `Domain` : Logique métier partagée de lecture (priority queue, lecture directe, historiques) et de synchronisation.
- `Data` : Base de données SQLite partagée via **Room Multiplatform** (`commonMain`). Moteurs de lecture audio natifs spécifiques à la plateforme via des implémentations ciblées (`jvmMain` utilisant des API de bas niveau du système, `wasmJsMain` utilisant l'élément HTML5 Audio).

## Dépendances majeures
- **Compose Multiplatform** pour le rendu graphique matériellement accéléré par Skia.
- **Room Multiplatform** pour la base de données SQLite commune.
- **Ktor HTTP Client** (ou équivalent multiplateforme) pour les requêtes au serveur FastAPI.
- **Moteur Audio Natif** : Appels directs aux API audio du système d'exploitation en Bureau (Windows Media Foundation, CoreAudio, ALSA) et à l'API Web Audio / Element HTML5 Audio pour le Web.

## Principes de performance pour les joueurs
- **Rendu suspendable** : Lorsque l'application est réduite dans la zone de notification (System Tray) via le bouton de fermeture (`X`), les cycles de rendu de Skia sont désactivés pour annuler la consommation CPU/GPU. Le bouton de réduction standard (`-`) minimise normalement l'application dans la barre des tâches.
- **ZGC Générationnel** : Utilisation du ramasse-miettes de pointe de JDK 21 pour limiter les temps de pause à moins de 1 milliseconde, éliminant tout impact sur le framerate des jeux vidéo.
- **Virtual Threads** : Utilisation des threads virtuels de Project Loom (JDK 21) pour gérer de manière ultra-légère les accès fichiers (scan de la bibliothèque) et les transactions de base de données en arrière-plan.

## Code Mapping Cible
- `shared/src/commonMain/kotlin/` : Code partagé contenant les composants Compose, le thème d'AURA, et les ViewModels.
- `shared/src/jvmMain/kotlin/` : Extensions Bureau (code JNI de lecture native, hooks clavier multimédias, gestion du System Tray).
- `shared/src/wasmJsMain/kotlin/` : Extensions Web (Service Workers, ponts JavaScript/Wasm pour la lecture audio et le stockage OPFS).
- `desktopApp/` : Point d'entrée de l'application Bureau, configuration Gradle de la JVM 21 d'exécution et options de lancement (ZGC).
- `webApp/` : Point d'entrée de l'application Web, ressources HTML/JS hôtes et compilation Wasm.
