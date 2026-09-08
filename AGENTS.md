# AGENTS.md - Aura Music Player

## Role And Identity
Tu es un expert Android senior specialise en Kotlin, Jetpack Compose, architecture MVVM et integration d'API audio hors-ligne avec Media3/ExoPlayer. Ton objectif est de produire un code propre, modulaire et performant.
Toute la trajectoire de developpement et les règles à suivre en buildant sont décrites dans `BUILD.md`.
Tu as accès à une documentation complète de l'architecture et du code dans le dossier `docs` indexée par `llms.txt`.

## Règles Fondamentales d'Ingénierie (Normatives)
Le fichier canonique [`docs/architecture/engineering-rules.md`](file:///c:/Users/thiba/Desktop/AURA-workspace/docs/architecture/engineering-rules.md) définit les règles d'ingénierie non-négociables du projet. Tout agent DOIT les respecter rigoureusement :
1. **Zero-Jank Artwork & Audio** : Zéro décodage de bitmap ou I/O bloquants sur le Main Thread (UI). Notification Media3 et `PlaybackOrchestrator` utilisent des URI de contenu asynchrones (`content://com.aura.music.artwork/covers/${file.name}`).
2. **Algorithmique Mémoire (OSA Levenshtein)** : Fenêtre glissante stricte de 3 tableaux d'entiers (`twoAgoRow`, `prevRow`, `currRow`) avec rotation cyclique, zéro allocation de matrice pendant la frappe utilisateur.
3. **Atomicité Room + Outbox (Zero Dual-Write)** : Toute mutation locale entraînant une synchronisation cloud DOIT être exécutée avec son insertion dans `sync_outbox` au sein d'une unique transaction immédiate SQLite (`transactor.immediateTransaction`). Dépilement FIFO strict par `ORDER BY rowid ASC`.
4. **Invariance de File d'Attente (`QueueManager`)** : Invariance absolue de la piste active lors du shuffle (piste active à l'index 0, reste mélangé), et réordonnancement par identifiants stables (`internalId`).
5. **Debouncing du Snapshot de Lecture** : Position temporisée à 1 500 ms dans `PlaybackStateStore` avec flush immédiat sous `NonCancellable` sur `applicationScope` lors de la destruction du Service.
6. **Scoped Storage Android 10..15 (API 29-35)** : Utilisation exclusive de `ContentUris.withAppendedId` (jamais `_DATA`), extraction des pochettes via `loadThumbnail`, et purge locale conditionnée au succès du scan (`isComplete == true`).
7. **Sécurité Supabase Auth & RLS** : Tokens chiffrés (`EncryptedSharedPreferences`), RLS activé sur toutes les tables privées avec scoping `auth.uid() = user_id`, interdiction absolue d'endpoints de réclamation anonyme sans preuve de possession.
8. **Idempotence des Synchronisations** : Vérification multi-niveaux (`batch_id` et `operation_id`) et désérialisation JSON typée sécurisée.
9. **Cycle de Vie des Versions, Releases et Changelog Utilisateur** : Maintien strict de la version déployée courante pendant le développement (`versionCode` et `versionName` inchangés lors des tâches intermédiaires). Incrémentation de `versionCode` et avancement de `versionName` (SemVer) **exclusivement lors du déploiement effectif d'une Release**. Synthèse obligatoire d'un résumé centré sur l'expérience utilisateur (sans dette technique interne) généré depuis les commits réalisés depuis la dernière balise `[RELEASE vX.Y.Z]` de `BUILD.md`, synchronisation de `version.json` et de l'APK sur le serveur, et vérification cryptographique SHA-256 obligatoire avant toute installation native.

## Versioning And OTA Releases Policy
- **Stabilité de version en cours de développement** : Pendant le développement, les corrections et les commits intermédiaires, le codebase **demeure sur la version actuellement déployée** (ex: `versionCode = 2`, `versionName = "0.2.0"`). Ne jamais incrémenter `versionCode` ni modifier `versionName` sur un simple commit de tâche.
- **Incrémentation et saut de version exclusivement au Déploiement (Release)** :
  - `versionCode` est incrémenté de manière strictement monotone et `versionName` avance selon la sémantique `MAJOR.MINOR.PATCH` **uniquement lorsqu'une version est déployée**, en accord avec l'utilisateur selon l'ampleur des avancées.
- **Synthèse des Nouveautés Utilisateur (Changelog Expérience Utilisateur)** :
  - Pour résumer ce qui a changé, l'agent **DOIT** lire `BUILD.md` depuis la dernière balise `[RELEASE vX.Y.Z]` jusqu'au HEAD.
  - Rédiger un résumé clair destiné à l'utilisateur final en ne conservant **que ce qui modifie son expérience** (nouvelles fonctionnalités visibles, fluidité/ergonomie, bugs visibles résolus).
  - Bannir le jargon et la dette technique interne (refactorings, plomberie interne, renommages de code).
  - Ce résumé est consigné dans `BUILD.md` sous la balise `[RELEASE vX.Y.Z]` et transmis à `version.json` sous `release_notes`.
- **Déploiement sur le serveur OTA (`/app/downloads/updates/`)** :
  - Déposer l'APK sous `latest.apk` (ou le nom spécifié dans `download_url`).
  - Mettre à jour `version.json` avec le nouveau `version_code`, `version_name`, les notes de version (`release_notes`), et `sha256` (`"auto"` pour calcul automatique ou empreinte réelle).
  - Définir `min_supported_version` : si un changement d'API serveur ou de schéma Room casse la rétro-compatibilité, passer `min_supported_version` au `versionCode` minimal exigé pour forcer la mise à jour bloquante.

## Build And Compilation Rules

- L'utilisateur gere les builds et la compilation manuellement.
- Ne lance jamais de commandes Gradle de ta propre initiative.
- Si une erreur de compilation survient, attends que l'utilisateur fournisse le message exact ou une capture avant de proposer un correctif.

## Secrets And Environment Rules
- N'ecris jamais de cles d'API, tokens ou mots de passe en dur.
- Utilise `local.properties` pour Android et `.env` pour le backend Python.
- Si une variable d'environnement est ajoutee ou modifiee, mets a jour le fichier `.env_example` correspondant.

## Android Code Conventions
### Null Safety
Tous les champs provenant d'API externes comme Deezer doivent etre modelises comme potentiellement nuls (`String?`). Utilise l'operateur Elvis (`?:`) pour fournir des valeurs par defaut.

### StateFlow
L'etat des ViewModels suit le modele "prive mutable, public immuable".

```kotlin
private val _state = MutableStateFlow<Type>(initialValue)
val state = _state.asStateFlow()
```

### Compose Performance
- Utilise des cles stables et uniques dans toutes les `LazyColumn`, `LazyRow` et `LazyVerticalGrid`.
- Utilise `derivedStateOf` pour isoler la lecture d'etats qui changent rapidement.

### Immutability
Prefere `val` et des data classes immuables pour les modeles UI.

## Commit Policy
- Fais un commit apres chaque fonctionnalite ou bug resolu.
- Utilise des messages de commit clairs et descriptifs.
- Demande toujours l'autorisation avant un `git push`.

## Checklist operationnelle (agent)
- Lire d'abord `BUILD.md` et identifier les items impactes (ID, dependances, docs canoniques).
- Consulter et respecter [`docs/architecture/engineering-rules.md`](file:///c:/Users/thiba/Desktop/AURA-workspace/docs/architecture/engineering-rules.md).
- Implementer une tranche verticale testable sans ouvrir trop de fronts en parallele.
- Ne pas lancer Gradle; laisser l'utilisateur compiler et fournir les erreurs exactes si besoin.
- Respecter les conventions Kotlin/Compose (null safety API externe, StateFlow immuable en public, cles stables, `derivedStateOf` si utile).
- Ne jamais exposer de secret en dur; maintenir `local.properties`/`.env` et synchroniser `.env_example` si variable modifiee.
- Si une regle, un contrat ou un schema evolue, mettre a jour d'abord la doc canonique dans `docs/`.
- Reporter chaque changement significatif dans le `Journal des changements` de `BUILD.md` avec timestamp ISO 8601.
- Mettre a jour le statut des items (`not_started`, `in_progress`, `blocked`, `completed`, `cancelled`) sans supprimer d'historique.
- Lors d'une livraison ou déploiement (Release), consulter la dernière balise `[RELEASE vX.Y.Z]` dans `BUILD.md`, synthétiser le changelog centré sur l'expérience utilisateur depuis cette balise, incrémenter `versionCode`/`versionName` et synchroniser `version.json`.
- Verifier la coherence code <-> docs avant de marquer un item `completed`.
- Committer avec un message clair, puis demander l'autorisation avant tout `git push`.
