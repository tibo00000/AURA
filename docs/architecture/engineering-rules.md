# Règles d'Ingénierie et d'Architecture AURA

Ce document constitue la référence normative absolue pour toutes les implémentations et refactorisations dans le projet AURA. Chaque agent ou développeur intervenant sur le codebase **DOIT** se conformer strictement à ces règles fondamentales.

---

## 1. Moteur Audio, Artwork et Zero-Jank (Android & Multiplateforme)
- **Interdiction absolue d'I/O et de décodage Bitmap sur le Main Thread (UI)** :
  - Ne jamais appeler `BitmapFactory.decode*`, de lecture de flux ou de requêtes réseau bloquantes sur le thread principal.
  - La notification de lecture Media3 (`PlaybackService`) et l'orchestrateur (`PlaybackOrchestrator`) doivent utiliser exclusivement des URI de contenu asynchrones : `content://com.aura.music.artwork/covers/${file.name}` ou des requêtes Coil asynchrones sans bloquer le Service.
- **Sécurisation de `ArtworkContentProvider`** :
  - Accès restreint au mode lecture seule strict (`mode == "r"`).
  - Protection systématique contre le *Path Traversal* : valider que `File(target).canonicalPath.startsWith(allowedDir.canonicalPath)`.

---

## 2. Algorithmique & Performance Mémoire (Recherche Fuzzy & OSA)
- **Distance OSA (Optimal String Alignment) / Damerau-Levenshtein à mémoire $O(\min(N, M))$** :
  - Ne jamais allouer de matrice complète bidimensionnelle $(N+1) \times (M+1)$ pendant la saisie utilisateur.
  - L'algorithme doit impérativement utiliser une fenêtre glissante à **3 tableaux d'entiers** (`twoAgoRow`, `prevRow`, `currRow`) avec rotation cyclique pour éliminer le GC thrashing.
  - Formule exacte de transposition : lorsque `a[i-1] == b[j-2]` et `a[i-2] == b[j-1]`, le coût de transposition s'évalue via `twoAgoRow[j-2] + 1`.

---

## 3. Persistance Locale & Atomicité Room + Outbox (Zero Dual-Write)
- **Atomicité Transactionnelle SQLite obligatoire pour toute mutation synchronisée** :
  - Toute opération locale qui génère une entrée de synchronisation (création/renommage/suppression de playlist, ajout/suppression de piste, toggle de like, modification de métadonnées) **DOIT** exécuter l'écriture dans la table Room et l'insertion dans `sync_outbox` au sein d'une **unique transaction immédiate** :
    ```kotlin
    database.useWriterConnection { transactor ->
        transactor.immediateTransaction {
            database.playlistDao().insertPlaylist(...)
            database.syncOutboxDao().insert(outboxOp)
        }
    }
    ```
- **Dépilement FIFO Monotone de l'Outbox** :
  - Le dépilement de `sync_outbox` doit impérativement utiliser `ORDER BY rowid ASC` (insensible aux dérives ou ajustements d'horloge de l'appareil client).

---

## 4. Invariance et Déterminisme de la File d'Attente (`QueueManager`)
- **Invariance absolue de la piste active (`currentTrack`)** :
  - Toute modification de la file d'attente (activation/désactivation du shuffle, réordonnancement, suppression) doit préserver la piste actuellement en cours de lecture sans coupure ni saut d'index.
- **Shuffle déterministe avec partitionnement** :
  - La piste active reste toujours en tête de file (`mainQueueTracks[0]`) avec `currentIndex = 0`.
  - Seules les pistes restantes (`[1..lastIndex]`) sont mélangées.
- **Réordonnancement et suppressions par ID stable (`internalId`)** :
  - Les interactions de réordonnancement (drag & drop Compose) doivent cibler des identifiants stables (`fromInternalId`, `toInternalId`) et non des indices bruts éphémères.

---

## 5. Debouncing et Cycle de Vie du Snapshot de Lecture
- **Debouncing de la position de lecture** :
  - L'enregistrement du snapshot de position (`PlaybackStateStore`) doit être temporisé à un intervalle de 1 500 ms pour éviter de saturer la base de données Room.
- **Flush immédiat garanti à la fermeture** :
  - Lors de l'arrêt du service, de la mise en pause ou de la destruction (`onDestroy`), un flush synchrone immédiat du snapshot **DOIT** être garanti sous `withContext(NonCancellable)` dispatché sur un CoroutineScope de niveau application (`applicationScope`).

---

## 6. MediaStore & Scoped Storage (Android 10..15 / API 29-35)
- **Requêtes MediaStore modernisées** :
  - Utiliser systématiquement `ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)`.
  - Ne jamais dépendre de la colonne dépréciée `MediaStore.Audio.Media.DATA` sur Android 10+ (API 29+).
  - Utiliser `DISPLAY_NAME` et `RELATIVE_PATH` pour identifier et exclure les fichiers indésirables (WhatsApp, notes vocales, opus).
- **Miniatures de pochettes haute performance** :
  - Privilégier `ContentResolver.loadThumbnail(audioUri, Size(width, height), null)` sur API 29+.
- **Purge sécurisée anti-perte de données** :
  - La purge des pistes locales lors d'un scan ne doit être déclenchée que si le scan MediaStore s'est terminé avec succès (`isComplete == true`) sous protection `try/catch(SecurityException)`.

---

## 7. Sécurité, Supabase Auth & Row Level Security (RLS)
- **Zéro secret ou token codé en dur** :
  - Utiliser `local.properties` (Android) et `.env` (Backend).
  - Sur Android, stocker les tokens de session via `EncryptedSharedPreferences` (AES256-GCM / MasterKey Keystore).
- **Isolation stricte des données privées (RLS)** :
  - Toutes les tables de données utilisateur PostgreSQL/Supabase doivent avoir Row Level Security activé avec scoping sur `auth.uid() = user_id`.
  - Le backend FastAPI doit exécuter les requêtes utilisateur via un client Supabase scopé au JWT utilisateur (`get_user_supabase_client(user_jwt)`).
  - **Interdiction absolue d'endpoints ouverts de réclamation d'UUID anonyme** sans preuve cryptographique de possession.

---

## 8. Idempotence & Robustesse de la Synchronisation Réseau
- **Idempotence multi-niveaux** :
  - Les requêtes de push batch doivent vérifier et persister l'idempotence au niveau du lot (`batch_id` dans `processed_batches`) et au niveau individuel (`operation_id` dans `processed_operations`).
- **Désérialisation JSON tolérante aux pannes** :
  - Toujours utiliser des méthodes d'extraction typées sécurisées (`.string()`, `.boolean()`, `.int()`, `.long()`, `?: default`) pour désérialiser les snapshots serveur, sans supposer de types primitifs stricts.

---

## 9. Conventions de Code Kotlin & Jetpack Compose
- **Null Safety des API externes** : Tous les champs externes (Deezer, Ktor) doivent être modélisés en `String?` avec repli Elvis (`?:`).
- **StateFlow UI** : Respecter le pattern privé mutable `_state: MutableStateFlow`, public immuable `state: StateFlow`.
- **Compose Performance** :
  - Fournir des clés stables (`key = { it.id }`) dans toutes les `LazyColumn`, `LazyRow` et `LazyVerticalGrid`.
  - Utiliser `derivedStateOf` pour isoler les lectures d'états haute fréquence (sliders, scrolls).

---

## 10. Gestion des Versions, Releases et Changelog Utilisateur
- **Incrémentation Monotone Exclusivement au Déploiement** :
  - Pendant les phases de développement, de résolution de bugs et les commits intermédiaires, le codebase **demeure sur la version actuellement déployée** (ex: `versionCode = 2`, `versionName = "0.2.0"`).
  - L'incrémentation strictement monotone de `versionCode` (entier) et l'avancement de `versionName` (`MAJOR.MINOR.PATCH`) dans `androidApp/build.gradle.kts` interviennent **uniquement lors du déploiement effectif d'une nouvelle version (Release)**, en accord avec l'utilisateur selon l'ampleur des avancées.
- **Synthèse du Changelog Utilisateur (User-Facing Release Notes)** :
  - Lors de la préparation d'une release, l'agent **DOIT** parcourir l'ensemble des commits et entrées du journal réalisés depuis la dernière version déployée enregistrée dans `BUILD.md`.
  - Il rédige une synthèse claire en langage naturel destinée à l'utilisateur final, comprenant **exclusivement les éléments qui transforment ou enrichissent son expérience** :
    1. Nouvelles fonctionnalités visibles (actions, boutons, nouveaux écrans, intégrations).
    2. Améliorations ergonomiques et de fluidité (transitions, raccourcis, réactivité multi-écrans).
    3. Corrections de bugs vécus (résolution de crashs, disparitions d'éléments, erreurs réseau visibles).
  - **Exclusion stricte** de la dette technique interne : refactoring de packages, renommages de fonctions privées, optimisations invisibles ou plomberie interne sans impact perçu.
- **Balise de Release Obligatoire dans `BUILD.md`** :
  - Tout déploiement est consigné dans `BUILD.md` sous une balise explicite :
    ```markdown
    ### 🚀 [RELEASE vX.Y.Z] - YYYY-MM-DD (versionCode: N)
    **Nouveautés pour l'utilisateur :**
    - Résumé clair point par point
    ```
  - **Règle absolue pour tout agent** : Pour résumer les nouveautés d'une future version, l'agent doit se référer à la balise `[RELEASE ...]` la plus récente dans `BUILD.md` et agréger tous les commits intervenus depuis ce point d'ancrage.
- **Synchronisation du Serveur OTA (`version.json`)** :
  - Tout déploiement d'APK sur le serveur sous `/app/downloads/updates/` est accompagné de la mise à jour de `version.json` :
    ```json
    {
      "version_code": 2,
      "version_name": "0.2.0",
      "download_url": "/app/updates/latest.apk",
      "sha256": "auto",
      "release_notes": "Résumé utilisateur généré depuis les commits récents",
      "min_supported_version": 1
    }
    ```
  - `sha256` : `"auto"` (calculé par le serveur) ou empreinte hexadécimale exacte.
  - `min_supported_version` : rehaussé uniquement si une rupture de contrat d'API ou de schéma Room rend les anciennes versions incompatibles.
- **Sécurité et Intégrité Client (Anti-Tampering)** :
  - Le client Android valide impérativement le hash SHA-256 du fichier APK téléchargé avant toute installation.
  - En cas de discordance, le fichier est détruit immédiatement.
  - L'installation native utilise obligatoirement `FileProvider` avec `FLAG_GRANT_READ_URI_PERMISSION`.

