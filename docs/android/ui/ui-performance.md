# Optimisation Des Performances UI & Zero-Jank Scroll

## 1. Objectif
Maintenir une interface Jetpack Compose à 60/120 FPS constants, sans saccade (jank), sans allocations mémoire répétées au défilement, avec un lecteur persistant et de très grandes listes musicales.

---

## 2. Moteur d'Indexation & Matching O(1) (`TrackLookupIndex`)

Au lieu de parcourir l'ensemble de la bibliothèque locale et des fichiers Cloud via des boucles imbriquées \(O(N \times M)\) dans chaque item de liste Compose :
- **Précálcul O(1)** : Dès le chargement des listes, `TrackLookupIndex` construit des maps d'indexation inversée en mémoire :
  - Index par ID exact (`idIndex`).
  - Index par triplet canonique normalisé (`title + artistName + albumTitle`).
  - Index par paire normalisée (`title + artistName`).
- **Résolution instantanée** : Lors du rendu de chaque ligne (`SharedTrackRowItem` ou `InteractiveOnlineTrackRow`), la recherche de correspondance s'effectue en temps constant \(O(1)\).
- **Priorité aux fichiers physiques** : En cas de doublons ou de correspondances multiples, la priorité absolue est donnée aux pistes disposant d'un `contentUri` non nul présent sur le stockage.

---

## 3. Cache Mémoire & Éradication des Allocations au Scroll

### A. Mémoïsation des IDs Deezer (`DeezerIdMemoizer`)
- Les pistes enrichies ou issues du catalogue en ligne utilisent des identifiants encodés ou composites.
- Un cache LRU `DeezerIdMemoizer` mémorise les résultats de décodage/conversion pour éliminer 100% des opérations de décodage Base64 et de parsing de chaînes à chaque recomposition d'item.

### B. Formatage Léger des Durées (`FastTimeFormatter`)
- Le calcul des durées (ex: `3:45`) dans les cellules de liste est assuré sans instanciation d'objets `java.text.SimpleDateFormat`, `String.format()` ou expressions régulières, supprimant la pression sur le Garbage Collector (GC).

### C. Instanciation Paresseuse des Menus Contextuels
- Les listes d'options contextuelles (`ContextMenuItem`) ne sont pas allouées pour tous les items affichés, mais créées à la demande lors du clic sur le bouton d'options `...`.

---

## 4. Stabilité du Défilement & Mises à Jour Atomiques

- **Préservation du `LazyListState`** : L'état de défilement est mémorisé via `rememberLazyListState()` et passé explicitement à `AuraLazyColumn` / `LazyColumn`.
- **Zéro vidage synchrone** : Lors d'un rafraîchissement (like, modification de tag, ajout en playlist), les listes `SnapshotStateList` ne sont plus vidées de façon synchrone (`clear()`) avant un appel asynchrone suspendu. Les données sont chargées en arrière-plan puis remplacées de manière atomique, évitant la destruction des slots Compose et la réinitialisation de la position de scroll à 0.

---

## 5. Règles Générales Compose & Media3

- Fournir une clé unique et stable (`key = { ... }`) pour chaque item de `LazyColumn`.
- Utiliser `contentType` pour permettre le recyclage optimal des slots Compose.
- Isoler les lectures d'états rapides (ex: progression de lecture, position du slider) avec `derivedStateOf`.
- Maintenir une session Media3 unique (`MediaLibraryService`) partagée pour le MiniPlayer, le grand lecteur et Android Auto.
- Charger et redimensionner les pochettes via Coil avec cache mémoire et disque (`AsyncImage`).
