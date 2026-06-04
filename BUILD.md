# BUILD

## Objectif
Fournir un fichier unique de pilotage pour que tout agent ou contributeur puisse reprendre la construction d'AURA sans ambiguite sur l'etat du chantier.

## Regles strictes
- Ce fichier suit l'execution du projet. Il ne remplace jamais les documents canoniques de produit, domaine, API ou base de donnees.
- Toute information normative decouverte pendant l'implementation doit etre reportee dans le document canonique concerne, puis tracee ici dans le journal des changements.
- Les statuts autorises sont uniquement `not_started`, `in_progress`, `blocked`, `completed`, `cancelled`.
- Chaque item de travail doit avoir un identifiant stable.
- Chaque item de travail doit indiquer un perimetre clair, un statut unique et les dependances majeures si elles existent.
- Une ligne terminee ne doit pas etre reecrite en prose libre. Elle doit seulement changer de statut et, si necessaire, recevoir une note courte.
- Aucun marqueur du type `[TODO]`, `[WIP]`, `[NOUVEAU]`, `a finir`, `en cours de reflexion` n'est autorise dans ce fichier.
- Les changements de documentation, de schema ou de contrats decouverts pendant la construction doivent etre inscrits dans `Journal des changements` avec une date et une heure au format ISO 8601.
- Si un item devient obsolet, il passe en `cancelled` au lieu d'etre supprime.
- Si un nouvel item apparait, il est ajoute dans la bonne section avec un nouvel identifiant stable.
- Lorsqu'un item passe en `completed`, l'agent doit verifier que les fichiers canoniques lies ont ete mis a jour si necessaire.

## Format obligatoire des items
Chaque item suit cette structure :

| ID | Area | Work Item | Status | Dependencies | Canonical Docs | Notes |
|---|---|---|---|---|---|---|

Contraintes :
- `ID` est stable et unique.
- `Area` prend une valeur courte comme `android`, `backend`, `infra`, `docs`.
- `Work Item` decrit un livrable concret.
- `Status` utilise uniquement les valeurs autorisees.
- `Dependencies` reference des IDs ou `none`.
- `Canonical Docs` reference les fichiers de spec de verite.
- `Notes` reste courte, factuelle et non narrative.

## Regles pour le journal des changements
- Chaque entree commence par un timestamp ISO 8601 avec fuseau.
- Chaque entree indique le type de changement : `code`, `docs`, `schema`, `api`, `decision`.
- Chaque entree mentionne les fichiers canoniques impactes.
- Chaque entree explique le changement en une phrase courte.
- Une entree de journal n'est jamais un substitut a une mise a jour du document canonique.

## Etat global actuel
- La base documentaire de reference est en place.
- Le code applicatif Android ou backend a été commencé, se référer au code workboard pour l'état actuel.

## Trajectoire globale

### Regles de priorisation
- Toujours livrer d'abord ce qui rend l'application localement utilisable.
- Toujours preferer une tranche verticale executable a une base trop abstraite.
- Ne pas demarrer une phase si la phase precedente n'a pas produit un resultat testable.
- Ne pas ouvrir simultanement trop de fronts. La priorite suit l'ordre des phases ci-dessous.
- Les integrations cloud et les optimisations n'arrivent qu'apres un coeur local fiable.

### Phase 1 - Fondations executables
Objectif :
- rendre possible le premier lancement des projets Android et backend

Priorite :
- `AND-001`
- `SRV-001`
- `INF-001`
- `INF-002`

Resultat attendu :
- un projet Android demarre
- un backend FastAPI demarre
- la configuration locale est documentee et executable

### Phase 2 - Coeur local Android
Objectif :
- rendre AURA utile sans backend

Priorite :
- `AND-002`
- `AND-003`
- `AND-004`
- `AND-006`
- `AND-007`

Resultat attendu :
- navigation fonctionnelle
- persistance locale `Room` et lecture `MediaStore`
- player local robuste
- playlists locales utilisables
- ecrans principaux navigables

### Phase 2.5 - Consolidation visuelle et hebergement
Objectif :
- figer la DA complete et l'hebergement always-on avant la recherche online

Priorite :
- `DOC-005`
- `INF-003`

Resultat attendu :
- direction visuelle complete documentee
- composants et compositions d'ecran figes
- strategie d'hebergement backend online clarifiee avant `SRV-002` et `AND-005`

### Phase 3 - Recherche hybride et UX complete
Objectif :
- finaliser la couche produit visible cote Android en verrouillant l'identite online, la fusion local + backend et la gouvernance reseau des enrichissements

Priorite :
- `SRV-002`
- `SRV-008`
- `AND-005`
- `AND-009`
- `AND-010`

Resultat attendu :
- recherche online disponible cote backend
- identifiants AURA backend opaques et politique de matching local/provider clarifies dans les contrats
- fusion local + online cote Android
- preferences reseau appliquees a tous les appels backend initiees par Android
- images locales et metadonnees enrichies persistees dans `Room`
- parcours `Search`, `Artist` et `Album` exploitables de bout en bout

### Phase 4 - Cloud sync durable
Objectif :
- synchroniser l'etat utilisateur durable entre appareils

Priorite :
- `SRV-004`
- `SRV-003`
- `SRV-007`

Resultat attendu :
- stockage cloud fonctionnel
- schema Postgres coherent avec des IDs AURA `TEXT` et UUID reserves au profil auth
- routes resource-oriented `/me/...` disponibles
- transport batch de sync `bootstrap`, `push-batch`, `pull-batch` implementable

### Phase 5 - Jobs et downloads
Objectif :
- ajouter les traitements asynchrones sans casser le coeur local

Priorite :
- `SRV-006`
- `AND-007`

Resultat attendu :
- infrastructure de jobs active
- suivi des downloads cote app
- contrat download generique branche de bout en bout

### Phase 6 - Recherche vectorielle et enrichissements
Objectif :
- brancher les capacites de recommandation et d'enrichissement avance

Priorite :
- `SRV-005`

Resultat attendu :
- `Qdrant` interrogeable
- mappings piste et payload vectoriel exploitables
- socle pret pour des recommandations futures

### Ce qu'il ne faut pas faire trop tot
- ne pas commencer par la sync cloud avant le coeur local
- ne pas commencer par `Qdrant` avant la recherche online et le modele cloud
- ne pas figer le telechargement autour d'une source reelle tant que la strategie produit n'est pas arretee
- ne pas ajouter d'etat UI ephemere dans les schemas de persistance
- ne pas reutiliser un ID local derive de `MediaStore` comme identifiant canonique backend sans mapping explicite

## Code Work Board

### Android
| ID | Area | Work Item | Status | Dependencies | Canonical Docs | Notes |
|---|---|---|---|---|---|---|
| AND-001 | android | Initialiser le projet Android Kotlin avec structure de modules minimale | completed | none | `docs/adrs/002-android-native-client.md`, `docs/android/app-architecture.md` | squelette Compose, structure Gradle et wrapper poses |
| AND-002 | android | Mettre en place la navigation Compose et le shell applicatif | completed | AND-001 | `docs/android/navigation.md`, `docs/product/navigation.md` | graphe principal, surfaces detail et mini-player shell poses |
| AND-003 | android | Implementer la couche locale `Room` et l'integration `MediaStore` | completed | AND-001 | `docs/android/local-persistence.md`, `docs/android/room-schema.md`, `docs/android/room-relationships.md` | base Room, import local MediaStore et settings par defaut poses |
| AND-004 | android | Implementer le moteur player Media3 et les regles de queue | completed | AND-001 | `docs/android/player/architecture.md`, `docs/android/player/queue-rules.md`, `docs/domain/playback-model.md` | priority queue en memoire, PlaybackService + QueueManager + Orchestrator + PlayerViewModel poses, ecran Player minimal fonctionnel |
| AND-005 | android | Implementer l'ecran `Search` avec orchestration local + online | completed | AND-002, AND-003, SRV-002 | `docs/android/screens/search.md`, `docs/product/user-flows.md`, `docs/server/api-contract.md` | UX validee avec TabRow Bibliotheque/En ligne, fusion local-first et integration detail/artist/album online |
| AND-006 | android | Implementer la gestion des playlists locales et leur UI | completed | AND-002, AND-003 | `docs/android/screens/playlists.md`, `docs/product/user-flows.md` | create, rename, delete, add local track, remove, reorder et lecture contexte playlist poses |
| AND-007 | android | Implementer les ecrans `Home`, `Library`, `Downloads`, `Settings`, `Player` complet et refonte UI Playlists | completed | AND-002, AND-003, AND-004 | `docs/android/screens/home.md`, `docs/android/screens/library.md`, `docs/android/screens/downloads.md`, `docs/android/screens/settings.md`, `docs/android/screens/player.md`, `docs/android/screens/player-layout.md`, `docs/android/screens/playlists-layout.md` | briques techniques posées avec succès (téléchargements asynchrones, flux de secours 5 choix YTM et cookies WebView YouTube), hors finitions de l'interface graphique |
| AND-008 | android | Coder le Theme.kt (police Outfit, Couleurs) et l'appliquer au shell, Home et Library | completed | DOC-006 | `docs/android/screens/home-layout.md`, `docs/android/screens/library-layout.md`, `docs/android/ui/design-system.md` | Theme.kt, Color.kt, Type.kt poses avec police Outfit. Applique a AuraApp (NavBar 4 onglets), HomeScreen et LibraryScreen. RouteScaffold enrichi (actions, style). Recherche locale filtree artistes/albums ajoutee. |
| AND-009 | android | Faire respecter les preferences reseau pour Search, enrichissements metadata et details media | completed | AND-003, AND-005 | `docs/android/screens/settings.md`, `docs/android/screens/search.md`, `docs/android/local-persistence.md`, `docs/android/room-schema.md` | `NetworkPolicyChecker` cree, `hybridSearch` parametre avec context+settings, `EnrichmentRepository` gate reseau + TTL 7j |
| AND-010 | android | Implementer les ecrans `Artist` et `Album` hybrides avec heroes locaux/online et cache d'images | completed | AND-003, AND-009, SRV-008 | `docs/android/screens/artist.md`, `docs/android/screens/artist-layout.md`, `docs/android/screens/album.md`, `docs/android/screens/album-layout.md`, `docs/android/room-schema.md` | `HybridArtistScreen` + `HybridAlbumScreen` + ViewModels avec chargement Room immediat + enrichissement asynchrone non-bloquant |
| AND-011 | android | Affiner les cartes hero/rails `Search`, `Artist` et `Album` local/online | completed | AND-005, AND-010 | `docs/android/screens/search.md`, `docs/android/screens/search-layout.md`, `docs/android/screens/artist-layout.md`, `docs/android/screens/album-layout.md` | hauteur des cartes partagees corrigee et images online branchees via `pictureUri` / `coverUri` |
| AND-012 | android | Implementer la synchronisation cloud offline-first / batch sync cote client Android | completed | AND-003, SRV-007 | `docs/server/sync-conflict-resolution.md`, `docs/server/sync-batch-api.md`, `docs/android/room-schema.md` | Room v4, outbox DAO, retrofits API, SyncRepository, SyncWorker, injection DI, et bouton Settings + lastSyncAt |
| AND-013 | android | Spécifier et implémenter le menu contextuel complet de SharedTrackRowItem et SelectPlaylistDialog | completed | AND-005, AND-007 | `docs/android/ui/components.md`, `docs/android/ui/component-states.md` | menu contextuel dynamique mémorisé, dialogue réutilisable purement UI et launchSingleTop raccordé |


### Backend
| ID | Area | Work Item | Status | Dependencies | Canonical Docs | Notes |
|---|---|---|---|---|---|---|
| SRV-001 | backend | Initialiser le projet FastAPI avec structure applicative minimale | completed | none | `docs/adrs/003-backend-fastapi-supabase-qdrant.md`, `docs/server/architecture.md` | base HTTP, config et `/health` poses |
| SRV-002 | backend | Implementer les endpoints online publics `health`, `search`, `artist`, `album` | completed | SRV-001, DOC-007 | `docs/server/api-contract.md`, `docs/server/providers/deezer.md`, `docs/server/database-postgres.md` | enveloppe JSON canonique, IDs backend opaques resolus cote serveur, routes detail chainables depuis `Search`, adapter Deezer et structure catalogue/mappings poses ; persistance catalogue durable laissee a `SRV-004` |
| SRV-003 | backend | Implementer les endpoints de sync cloud optionnelle `/me/...` | completed | SRV-001 | `docs/server/api-contract.md`, `docs/server/database-postgres.md` | sync user-scoped REST, reglages, favoris, playlists et snapshot de lecture en ligne |
| SRV-007 | backend | Implementer les endpoints batch `bootstrap`, `push-batch`, `pull-batch` pour la sync | completed | SRV-001, SRV-004 | `docs/server/sync-conflict-resolution.md`, `docs/server/sync-batch-api.md` | moteur de synchronisation en lot, idempotence et resolution des conflits complete |
| SRV-004 | backend | Implementer les tables et acces `Supabase / Postgres` | completed | SRV-001, DOC-007 | `docs/server/database-postgres.md`, `docs/server/postgres-relationships.md` | Integration et persistance client privilegee pour download_jobs terminee |
| SRV-005 | backend | Integrer `Qdrant` pour la recherche vectorielle et les mappings piste | not_started | SRV-001 | `docs/server/vector-search-qdrant.md`, `docs/server/api-sync-flows.md` | vecteurs plus payload |
| SRV-006 | backend | Implementer le systeme de jobs et l'API downloads generique | completed | SRV-001, SRV-004 | `docs/server/jobs.md`, `docs/server/api-contract.md`, `docs/server/api-sync-flows.md` | Faisabilite yt-dlp validee sur VPS et persistance PostgreSQL/Supabase OK |
| SRV-008 | backend | Exposer la resolution metadata pour entites locales et completer les payloads detail `artist` / `album` | completed | SRV-002 | `docs/server/api-contract.md`, `docs/server/providers/deezer.md` | `GET /resolve/artist` et `GET /resolve/album` implementes via `resolve_service.py` + `routes/resolve.py`, score textuel [0,1], ID AURA opaque retourne |

### Infrastructure et gouvernance
| ID | Area | Work Item | Status | Dependencies | Canonical Docs | Notes |
|---|---|---|---|---|---|---|
| INF-001 | infra | Definir les variables d'environnement et fichiers d'exemple | completed | SRV-001 | `docs/ops/env-vars.md`, `docs/server/security-and-secrets.md` | `.env.example` backend et exemple Android ajoutes |
| INF-002 | infra | Definir Docker et l'environnement local de dev backend | completed | SRV-001 | `docs/adrs/003-backend-fastapi-supabase-qdrant.md`, `docs/server/architecture.md` | Dockerfile, compose local et blueprint Render racine ajoutes |
| INF-003 | infra | Fixer une cible backend always-on avant la recherche online produit | completed | INF-002 | `docs/ops/hosting-strategy.md`, `docs/server/architecture.md`, `docs/server/api-contract.md` | Contabo VPS 10 choisi et backend deploye avec variables posees |
| GOV-001 | docs | Maintenir la coherence entre code, specs et index machine-friendly | in_progress | none | `docs/README.md`, `docs/documentation/style-guide.md`, `llms.txt`, `llms-full.txt` | activite continue |

## Done Before Code
| ID | Area | Work Item | Status | Dependencies | Canonical Docs | Notes |
|---|---|---|---|---|---|---|
| DOC-001 | docs | Poser la structure documentaire machine-friendly | completed | none | `docs/README.md`, `llms.txt`, `llms-full.txt` | base canonique en place |
| DOC-002 | docs | Documenter produit, navigation, user flows et ecrans | completed | DOC-001 | `docs/product/*`, `docs/android/screens/*` | socle UX documente |
| DOC-003 | docs | Documenter player, persistance, schemas et API | completed | DOC-001 | `docs/domain/*`, `docs/android/room-schema.md`, `docs/server/api-contract.md` | socle technique documente |
| DOC-004 | docs | Ajouter les diagrammes ER et les flux API orientes sync | completed | DOC-003 | `docs/domain/data-relationships.md`, `docs/android/room-relationships.md`, `docs/server/postgres-relationships.md`, `docs/server/api-sync-flows.md` | vues transverses disponibles |
| DOC-005 | docs | Consolider la DA complete et la strategie online backend-only | completed | DOC-002, DOC-003 | `docs/android/ui/*`, `docs/adrs/006-online-search-backend-only.md`, `docs/ops/hosting-strategy.md` | DA complete, backend-only search et hebergement always-on documentes |
| DOC-006 | docs | Generer et valider les schemas de layouts (-layout.md) ecran par ecran | in_progress | DOC-005 | `docs/android/screens/*-layout.md` | search-layout, album-layout et artist-layout documentes. Reste : player, downloads |
| DOC-007 | docs | Fixer la strategie d'identite entre `MediaStore`, IDs AURA backend et mappings provider | completed | DOC-003 | `docs/domain/entities.md`, `docs/android/room-schema.md`, `docs/server/api-contract.md`, `docs/server/database-postgres.md`, `docs/server/sync-conflict-resolution.md` | IDs API opaques en `TEXT`, IDs locaux non promus au cloud, matching via mappings |
| DOC-008 | docs | Formaliser la gouvernance reseau et l'enrichissement metadata local/online | completed | DOC-003 | `docs/android/screens/settings.md`, `docs/android/screens/search.md`, `docs/android/screens/artist.md`, `docs/android/screens/album.md`, `docs/android/local-persistence.md`, `docs/android/room-schema.md`, `docs/server/api-contract.md` | reglages reseau rendus normatifs, images locales persistees en Room, resolvers backend documentes |

## Handoff cible AND-005
- Consommer uniquement le backend AURA pour la recherche online ; aucun appel Deezer direct depuis Android.
- Continuer a afficher les suggestions locales des 3 caracteres pendant la saisie avec `Room` uniquement.
- Au submit, appeler `GET /search` et exploiter `data.query`, `data.best_match`, `data.tracks`, `data.artists`, `data.albums` dans l'enveloppe canonique.
- Considerer tous les `id` de reponse comme opaques ; ne jamais parser ni transformer les IDs backend cote Android.
- Utiliser les `id` retournes par `GET /search` tels quels pour naviguer vers `/artists/{id}` et `/albums/{id}` via les futures integrations Android.
- Conserver la fusion local + online cote Android : priorite d'affichage au local pour `Meilleur resultat` si le match local est fort, avec TabRow racine `Bibliotheque` / `En ligne`.
- Traiter le `best_match` backend comme un indice online et non comme l'autorite finale du hero Android.
- Prevoir un etat online non bloquant : si l'appel backend echoue, l'ecran garde la recherche locale et affiche une erreur online legere.
- Le perimetre `Search` ne comprend pas les playlists locales a ce stade.
- Nettoyer `SearchScreen` des boutons de test et remplacer le placeholder actuel par la vraie structure : barre sticky, suggestions locales, hero `Meilleur resultat`, TabRow racine, bloc local, sections online.
- Si des models Android sont ajoutes pour parser `SRV-002`, garder des champs nullable pour les donnees externes et des valeurs par defaut via Elvis.

## Handoff cible AND-009
- Les reglages `online_search_enabled` et `online_search_network_policy` deviennent la verite produit pour tous les appels backend Android lies a la recherche et aux enrichissements media.
- Aucun enrichissement reseau ne doit partir depuis les suggestions locales pendant la saisie.
- Les enrichissements d'image ou de metadata pour des entites locales visibles doivent ecrire leur resultat dans `Room`.
- Si le reseau est bloque par les reglages, l'application doit rester locale et afficher des placeholders sans erreur bloquante.
- Les appels doivent etre deduplices et limites aux entites visibles ou explicitement ouvertes.

## Handoff cible SRV-008
- Ajouter `GET /resolve/artist?name=...` et `GET /resolve/album?title=...&artist_name=...` selon le contrat canonique.
- Ces routes servent a enrichir une entite locale ne possedant pas encore d'ID backend.
- Les payloads detail `GET /artists/{id}` et `GET /albums/{id}` doivent rester hero-ready pour Android.
- Les IDs retournes restent opaques et reemployables tels quels par Android.

## Handoff cible AND-010
- Ouvrir `Artist` et `Album` locaux instantanement depuis `Room`, meme sans image.
- Si une image ou une metadata hero manque et que les reglages reseau l'autorisent, lancer une resolution backend puis persister le resultat.
- Distinguer clairement les heroes locaux, online et hybrides selon les layouts canoniques.
- Utiliser `artist_source_links` et `album_source_links` pour memoriser les resolutions backend associees aux entites locales.

## Handoff cible SRV-006
- Concevoir et implémenter le système de jobs asynchrones dans le backend (avec téléchargement temporairement simulé/stub).
- La persistance des jobs se fera dans PostgreSQL (via Supabase ou SQLAlchemy, selon SRV-004).
- Implémenter les routes d'API canoniques : `POST /downloads` (crée le job asynchrone), `GET /downloads` (liste les jobs), `POST /downloads/{id}/retry`, et `GET /jobs/{id}`.
- Toutes les routes de téléchargement requièrent une authentification par Bearer token.
- Gestion des cookies YouTube : l'utilisateur pourra soumettre ses cookies Netscape via un endpoint sécurisé `POST /admin/cookies` (protégé par un secret admin) pour mettre à jour le fichier `cookies.txt` partagé, ou via une variable d'environnement/paramètre utilisateur.

## Journal des changements
- 2026-06-04T02:05:00+02:00 | code | `android/app/src/main/java/com/aura/music/domain/player/PlaybackOrchestrator.kt`, `android/app/src/main/java/com/aura/music/data/player/QueueManager.kt`, `BUILD.md` | Correction du bouton précédent d'ExoPlayer et de la restauration de session : implémentation d'une fenêtre glissante à 3 items [précédent, courant, suivant] dans ExoPlayer, reconstruction de l'historique de contexte avant `startIndex` lors de la restauration, et persistance automatique de la session à chaque transition de morceau.
- 2026-06-04T00:20:00+02:00 | code | `android/app/src/main/java/com/aura/music/ui/AuraApp.kt`, `BUILD.md` | Amélioration de l'UX de la barre de navigation : ré-affichage de l'écran racine de l'onglet actif (via popBackStack vers la racine du tab) et rechargement de l'écran s'il est déjà affiché, et persistance du highlight sélectionné lors de la navigation dans les sous-écrans.
- 2026-06-03T23:51:00+02:00 | code | `android/app/src/main/java/com/aura/music/ui/AuraApp.kt`, `android/app/src/main/java/com/aura/music/ui/screens/LibraryAndDetailsScreens.kt`, `android/app/src/main/java/com/aura/music/ui/screens/SearchScreen.kt`, `BUILD.md` | Correction du flux de suppression physique sous Android 10+ : conservation visuelle du morceau pendant la demande de permission et ré-exécution réelle de la suppression de Room/MP3 suite à l'autorisation de l'utilisateur.
- 2026-06-03T23:26:00+02:00 | code | `android/app/src/main/java/com/aura/music/data/repository/LocalLibraryRepository.kt`, `BUILD.md` | Correction du bug de disparition des covers de musiques téléchargées au bout de 2 jours : déplacement du stockage physique des pochettes JPEG extraites du cache temporaire de l'application (context.cacheDir) vers le stockage persistant garanti (context.filesDir/covers/).
- 2026-06-03T02:10:00+02:00 | code, ui, compile | `android/app/src/main/java/com/aura/music/ui/AuraApp.kt`, `android/app/src/main/java/com/aura/music/ui/screens/LibraryAndDetailsScreens.kt`, `android/app/src/main/java/com/aura/music/ui/screens/PlaylistDetailScreenNew.kt`, `android/app/src/main/java/com/aura/music/ui/screens/SearchScreen.kt`, `BUILD.md` | Résolution des erreurs de compilation Kotlin : typage strict Unit des lambdas coroutines/safe-calls, qualification de PlayerEvent, et correction de signature de SearchOnlineTrackRowItem.
- 2026-06-03T02:05:00+02:00 | code, ui, performance | `android/app/src/main/java/com/aura/music/ui/AuraApp.kt`, `android/app/src/main/java/com/aura/music/ui/screens/LibraryAndDetailsScreens.kt`, `android/app/src/main/java/com/aura/music/ui/screens/PlaylistDetailScreenNew.kt`, `android/app/src/main/java/com/aura/music/ui/screens/SearchScreen.kt`, `BUILD.md` | Stabilisation et mémorisation (via remember et rememberUpdatedState) de toutes les lambdas d'actions événementielles passées à SharedTrackRowItem, et ajout de contentType pour le recyclage matériel dans Favorites, LibraryTracks, Playlists et Search (Résolution cause lag #1).
- 2026-06-03T01:40:00+02:00 | code, ui, performance | `android/app/src/main/java/com/aura/music/ui/AuraApp.kt`, `android/app/src/main/java/com/aura/music/ui/screens/HybridDetailScreens.kt`, `android/app/src/main/java/com/aura/music/ui/screens/LibraryAndDetailsScreens.kt`, `BUILD.md` | Correction des 3 causes critiques de lag : (1) Transformation de `TrackList` en extension `LazyListScope` pour virtualiser l'affichage des titres, (2) Optimisation des boucles O(N²) de favoris et titres locaux via `itemsIndexed` et pre-map `contextTracks`, (3) Scission de l'état du lecteur collecté par `AuraApp` (seuls `currentTrack` et `playbackState` distincts sont écoutés) afin d'éviter la recomposition totale lors des ticks de progression.
- 2026-06-02T23:42:00+02:00 | code, ui, layout | `android/app/src/main/java/com/aura/music/ui/screens/SandboxScreen.kt`, `BUILD.md` | Intégration du panneau de réglages et de la bannière d'information directement dans le conteneur LazyColumn pour rendre l'ensemble de l'écran Sandbox entièrement scrollable, et ajustement de l'espacement inférieur (bottom content padding à 120.dp) pour éviter toute obstruction par le lecteur réduit.
- 2026-06-02T23:25:00+02:00 | code, ui, debug | `android/app/src/main/java/com/aura/music/ui/screens/SandboxScreen.kt`, `android/app/src/main/java/com/aura/music/ui/AuraApp.kt`, `BUILD.md` | Raccordement de la page Sandbox à la base de données Room (LocalLibraryRepository) avec un commutateur permettant d'afficher et de réorganiser les pistes réelles de l'utilisateur pour tester les performances de requêtes locales et de chargement de pochettes réelles.
- 2026-06-02T22:20:00+02:00 | code, ui, debug | `android/app/src/main/java/com/aura/music/ui/screens/SandboxScreen.kt`, `android/app/src/main/java/com/aura/music/ui/screens/SettingsScreen.kt`, `android/app/src/main/java/com/aura/music/ui/AuraApp.kt`, `BUILD.md` | Création d'une page Sandbox de performance accessible depuis les paramètres pour isoler la source de lag des listes réorganisables en modifiant à chaud leurs spécificités (reorderable, headers, covers, clés stables et contentTypes).
- 2026-06-02T22:00:00+02:00 | code, ui, performance | `android/app/src/main/java/com/aura/music/ui/screens/PlayerScreen.kt`, `BUILD.md` | Ajout d'un sélecteur d'onglets (Lecteur / File) dans la TopBar et d'un bouton de redirection, permettant d'afficher séparément les contrôles et la file d'attente en pleine hauteur, ce qui résout le problème de masquage de la file d'attente sur petit écran tout en préservant l'isolation de performance du Drag & Drop.
- 2026-06-02T02:32:00+02:00 | code, ui, performance | `android/app/src/main/java/com/aura/music/ui/screens/PlayerScreen.kt`, `BUILD.md` | Refonte architecturale de PlayerScreen en isolant la section statique (artwork, contrôles) dans un Column racine et en plaçant la file d'attente réorganisable dans un LazyColumn avec weight(1f), éliminant complètement les calculs de coordonnées superflus lors des défilements rapides.
- 2026-06-02T02:20:00+02:00 | code, ui, performance | `android/app/src/main/java/com/aura/music/ui/screens/PlayerScreen.kt`, `BUILD.md` | Intégration du paramètre `contentType` sur l'ensemble de la `LazyColumn` du lecteur pour séparer les pools de recyclage matériel et éviter les freezes lors des flings violents, et ajout de clés stables explicites à tous les composants.
- 2026-06-02T02:15:00+02:00 | code, ui, performance | `android/app/src/main/java/com/aura/music/ui/screens/PlayerScreen.kt`, `BUILD.md` | Création de la branche `test/queue-perf-v2` et implémentation de l'optimisation des lambdas d'événements mémorisées via remember (onRemoveFromQueue/onRemoveFromMainQueue) et du cache derivedStateOf sur les listes de file d'attente pour stabiliser les identités de recomposition.
- 2026-06-02T01:50:00+02:00 | code, ui, performance | `android/app/src/main/java/com/aura/music/ui/screens/PlayerScreen.kt`, `BUILD.md` | Restauration des pochettes d'albums (covers) et du drag & drop sur l'ensemble de la file d'attente, tout en préservant l'extraction des lignes en Composables dédiés et l'isolation recompositionnelle de la progression (Optimisations 1 & 2 retenues).
- 2026-06-02T00:55:00+02:00 | code, ui, performance | `android/app/src/main/java/com/aura/music/ui/screens/PlayerScreen.kt`, `BUILD.md` | Extraction des composants de ligne de file d'attente (PriorityQueueItemRow, MainQueueItemRow) en fonctions Composable dédiées et isolées pour optimiser la recomposition et éliminer les saccades de défilement (scrolling stutters).
- 2026-06-02T00:45:00+02:00 | code, ui, test | `android/app/src/main/java/com/aura/music/ui/screens/PlayerScreen.kt`, `BUILD.md` | Création de la branche `test/remove-queue-covers` pour évaluer l'impact de la suppression des visuels d'albums (covers) dans la file d'attente \"À suivre\" sur la fluidité et les performances de défilement (scrolling lag).
- 2026-06-02T00:36:00+02:00 | code | `android/app/src/main/java/com/aura/music/domain/player/PlaybackOrchestrator.kt`, `android/app/src/main/java/com/aura/music/ui/player/PlayerViewModel.kt`, `android/app/src/main/java/com/aura/music/ui/screens/LibraryAndDetailsScreens.kt`, `android/app/src/main/java/com/aura/music/data/repository/LocalLibraryRepository.kt`, `BUILD.md` | Résolution du bug de perte d'état favori dans le lecteur lors du lancement d'une piste depuis n'importe où en déplaçant l'interrogation et la mise à jour de l'état \"liked\" dans le cycle de transition Media3 (onMediaItemTransition) du PlaybackOrchestrator.
- 2026-06-02T00:29:00+02:00 | code, ui | `android/app/src/main/java/com/aura/music/ui/screens/PlayerScreen.kt`, `android/app/src/main/java/com/aura/music/ui/player/PlayerViewModel.kt`, `android/app/src/main/java/com/aura/music/data/repository/LocalLibraryRepository.kt`, `android/app/src/main/java/com/aura/music/ui/AuraApp.kt`, `BUILD.md` | Nettoyage du menu contextuel du lecteur en enlevant la redondance \"Aimer\" pour garder l'option conditionnelle \"Ajouter/Retirer des favoris\", et résolution du bug de perte de l'état favori (cœur non rempli) lors des rafraîchissements de l'index en préservant le booléen `isLiked` de l'entité Room existante.
- 2026-06-02T00:09:00+02:00 | code, ui | `android/app/src/main/java/com/aura/music/ui/screens/ScreenSharedComponents.kt`, `android/app/src/main/java/com/aura/music/ui/screens/PlaylistDetailScreenNew.kt`, `android/app/src/main/java/com/aura/music/ui/screens/LibraryAndDetailsScreens.kt`, `android/app/src/main/java/com/aura/music/ui/screens/SearchScreen.kt`, `android/app/src/main/java/com/aura/music/ui/AuraApp.kt`, `BUILD.md` | Ajout de l'option \"Ajouter à la file d'attente\" dans tous les menus contextuels locaux : intégration dans ScreenSharedComponents (playlist/favorites), propagation dans PlaylistDetailScreenNew et LibraryAndDetailsScreens (favoris/titres), et injection de playerViewModel dans SearchScreen.kt (recherche locale) depuis AuraApp.kt. Les résultats en ligne sont exclus comme demandé.
- 2026-06-01T19:27:00+02:00 | code, ui | `android/app/src/main/java/com/aura/music/ui/AuraApp.kt`, `android/app/src/main/java/com/aura/music/ui/screens/SearchScreen.kt`, `BUILD.md` | Ajout d'une notification visuelle (Snackbar) cliquable lors du lancement d'un téléchargement : intégration du support de snackbar dans RouteScaffold (AuraApp.kt) stylisé aux couleurs de la charte graphique et configuration de SearchScreen.kt pour afficher une notification avec action de redirection instantanée vers l'écran des téléchargements.
- 2026-06-01T16:32:00+02:00 | code, ui | `android/app/src/main/java/com/aura/music/data/repository/DownloadRepository.kt`, `android/app/src/main/java/com/aura/music/ui/screens/LibraryAndDetailsScreens.kt`, `BUILD.md` | Correction de la lecture des morceaux téléchargés : renforcement de la synchronisation avec vérification triple (\"self-healing\") automatique (intégrité du fichier physique, taille > 0 et indexation Room \"downloaded\") afin de réparer les téléchargements dont le rapatriement a été bypassé par la synchronisation, et correction du callback onPlay de DownloadsScreen pour résoudre dynamiquement l'URI de fichier valide de la piste locale à la place de l'URI nulle codée en dur.
- 2026-06-01T15:05:00+02:00 | code | `android/app/src/main/java/com/aura/music/data/repository/LocalLibraryRepository.kt`, `BUILD.md` | Résolution de la perte d'image de couverture (coverUri) lors du rafraîchissement d'indexation locale : préservation automatique de l'URL de couverture existante de l'entité de base de données, et extraction de secours intelligente de l'image intégrée (embedded picture) via MediaMetadataRetriever enregistrée sous cacheDir/covers/ si la base de données Room locale a été vidée ou réinitialisée.
- 2026-06-01T00:58:52+02:00 | code, ui | `android/app/src/main/java/com/aura/music/ui/AuraApp.kt`, `android/app/src/main/java/com/aura/music/ui/screens/HybridDetailViewModels.kt`, `BUILD.md` | Correction des erreurs de compilation de AuraApp.kt en ajoutant les imports Jetpack Compose (produceState, rememberCoroutineScope) et coroutine (launch) manquants, puis intégration robuste du lanceur d'intent pour la suppression physique et du rafraîchissement manuel de l'UI locale après suppression dans les écrans hybrides Artiste et Album.
- 2026-06-01T00:52:00+02:00 | code, ui, schema | `android/app/src/main/java/com/aura/music/data/local/LocalEntities.kt`, `android/app/src/main/java/com/aura/music/data/local/AuraDaos.kt`, `android/app/src/main/java/com/aura/music/ui/screens/PlaylistDetailScreenNew.kt`, `android/app/src/main/java/com/aura/music/ui/screens/LibraryAndDetailsScreens.kt`, `android/app/src/main/java/com/aura/music/ui/screens/HybridDetailScreens.kt`, `android/app/src/main/java/com/aura/music/ui/screens/SearchScreen.kt`, `android/app/src/main/java/com/aura/music/ui/AuraApp.kt`, `android/app/src/main/java/com/aura/music/data/repository/LocalLibraryRepository.kt`, `BUILD.md` | Résolution complète des points non aboutis : support du Like dans les playlists locales ( Room v4 / jointures), propagation et raccordement intégral de toutes les actions contextuelles manquantes (Like, Playlist, Supprimer) sur les résultats de Bibliothèque et sur les écrans hybrides Artiste & Album, matching textuel d'IDs en ligne intelligent dans l'onglet recherche pour navigation en ligne, et suppression physique MediaStore robuste via ContentResolver assistée du PendingIntent système native d'Android.
- 2026-05-31T22:26:00+02:00 | code, ui | `android/app/src/main/java/com/aura/music/ui/screens/ScreenSharedComponents.kt`, `android/app/src/main/java/com/aura/music/ui/screens/SearchScreen.kt`, `android/app/src/main/java/com/aura/music/ui/screens/LibraryAndDetailsScreens.kt`, `android/app/src/main/java/com/aura/music/ui/screens/PlaylistDetailScreenNew.kt`, `android/app/src/main/java/com/aura/music/ui/AuraApp.kt`, `BUILD.md` | Alignement du menu contextuel de SharedTrackRowItem avec la source de vérité, implémentation du dialogue purement UI SelectPlaylistDialog et raccordement de launchSingleTop=true sur la navigation.
- 2026-05-31T20:56:00+02:00 | code | `android/app/src/main/java/com/aura/music/ui/screens/SettingsScreen.kt`, `BUILD.md` | Ajout du bouton "Rafraîchir l'index local" dans la carte de diagnostics de l'écran des paramètres, permettant de forcer manuellement le re-scan complet de l'appareil et la purge des anciens audios obsolètes de la base Room.
- 2026-05-31T20:53:00+02:00 | code | `android/app/src/main/java/com/aura/music/data/media/MediaStoreAudioDataSource.kt`, `android/app/src/main/java/com/aura/music/data/repository/LocalLibraryRepository.kt`, `BUILD.md` | Exclusion totale et stricte de tous les fichiers au format `.opus` (MIME audio/opus ou extension) de l'indexation de l'application pour empêcher définitivement les notes vocales WhatsApp et autres enregistrements de parasiter la bibliothèque musicale.
- 2026-05-31T20:50:00+02:00 | code | `android/app/src/main/java/com/aura/music/data/repository/LocalLibraryRepository.kt`, `BUILD.md` | Ajout du support complet du format `.opus` (et autres formats audio courants comme `.m4a` et `.wav`) pour l'indexation locale des fichiers stockés dans le répertoire de téléchargement privé, avec détection dynamique du type MIME.
- 2026-05-31T19:35:00+02:00 | code | `android/app/src/main/java/com/aura/music/data/media/MediaStoreAudioDataSource.kt`, `android/app/src/main/java/com/aura/music/data/repository/LocalLibraryRepository.kt`, `BUILD.md` | Ajout du filtrage par nom d'album "WhatsApp Audio" et amélioration de la précision du tri "Récents" (utilisation des dates réelles d'écriture ou modification des fichiers à la place de l'horodatage courant du scan).
- 2026-05-31T19:15:00+02:00 | code | `android/app/src/main/java/com/aura/music/data/media/MediaStoreAudioDataSource.kt`, `android/app/src/main/java/com/aura/music/data/repository/LocalLibraryRepository.kt`, `android/app/src/main/java/com/aura/music/core/AuraAppContainer.kt`, `BUILD.md` | Résolution de l'exclusion des fichiers WhatsApp (filtrage de l'arborescence dans MediaStore) et de l'indexation des musiques privées téléchargées hors-ligne de l'application (analyse automatique de filesDir/downloads/ avec extraction de métadonnées de secours via MediaMetadataRetriever).
- 2026-05-29T23:25:00+02:00 | code, ui | `android/app/src/main/java/com/aura/music/data/local/LocalEntities.kt`, `android/app/src/main/java/com/aura/music/data/local/AuraDaos.kt`, `android/app/src/main/java/com/aura/music/data/repository/LocalLibraryRepository.kt`, `android/app/src/main/java/com/aura/music/ui/screens/SearchScreen.kt`, `android/app/src/main/java/com/aura/music/ui/screens/LibraryAndDetailsScreens.kt`, `android/app/src/main/java/com/aura/music/ui/AuraApp.kt`, `BUILD.md` | Ajout des pages locales Titres (tri A-Z, Récents, Favoris) et Artistes (tri A-Z, Plus de titres, Récents) à la Bibliothèque, et déplacement du bouton Téléchargements vers l'onglet Recherche.
- 2026-05-29T22:49:00+02:00 | code | `android/app/...`, `BUILD.md` | Correction de la persistance réseau des modifications de réglages utilisateur : intégration de l'enregistrement automatique des mutations 'user_settings' dans 'sync_outbox' lors de chaque appel à setSyncEnabled, setOnlineSearchEnabled, setOnlineSearchNetworkPolicy et setStatsSyncNetworkPolicy (ce qui évite que le pull du serveur n'écrase les valeurs locales).
- 2026-05-29T22:45:00+02:00 | code | `android/app/...`, `BUILD.md` | Résolution de la violation de clé étrangère SQLite (code 787) lors de l'intégration des favoris et éléments de playlists synchronisés, via l'ajout d'une méthode 'ensureTrackStub' dans SyncRepository.kt insérant un track virtuel minimal si l'ID n'existe pas localement.
- 2026-05-29T22:42:00+02:00 | code | `android/app/...`, `BUILD.md` | Ajout d'un paramètre 'force = true' à performSync pour contourner la politique restrictive 'wifi_only' lors d'un déclenchement manuel depuis les paramètres (évitant le skip sur émulateur/mobile sans logs VPS).
- 2026-05-29T22:33:00+02:00 | code | `android/app/build.gradle.kts`, `android/app/src/main/java/com/aura/music/data/repository/SyncRepository.kt`, `BUILD.md` | Correction des erreurs de compilation Android : ajout de la dépendance gradle 'androidx.work:work-runtime-ktx' manquante et ajout de l'import de 'ServerChangeDto' dans SyncRepository.kt.
- 2026-05-29T22:25:00+02:00 | code, ui, schema | `android/app/...`, `BUILD.md` | AND-012 : Implémentation complète de la synchronisation client Android (Room v4 avec outbox, Retrofit API endpoints, SyncRepository, SyncWorker, et intégration UI dans SettingsScreen avec bouton de synchronisation manuelle et date/heure de dernière sync).
- 2026-05-29T21:59:00+02:00 | code, schema, api | `server/app/api/routes/me.py`, `server/app/api/routes/sync.py`, `server/app/services/sync_service.py`, `server/app/schemas/sync.py`, `server/app/schemas/me.py`, `server/scratch_sync_test.py`, `BUILD.md` | SRV-003 & SRV-007 : Implémentation complète de la Phase 4 (Cloud Sync Durable) avec création des tables de sync reliées, endpoints REST /me (réglages, favoris, playlists, snapshot) et moteur batch robuste (idempotence processed_operations, LWW occurred_at, décalage position et validation de token reorder).
- 2026-05-29T18:08:00+02:00 | code, schema, api | `server/app/services/download_service.py`, `server/app/db/supabase.py`, `server/app/domain/models.py`, `BUILD.md` | SRV-004 & SRV-006 : Migration complete de la persistance de l'etat des jobs et candidats vers PostgreSQL via Supabase avec integration du worker de telechargement, throttling de progression, et activation de la securite RLS (Row Level Security) sur download_jobs.
- 2026-05-29T13:42:00+02:00 | code, api, ui | `server/app/services/download_service.py`, `server/app/api/routes/downloads.py`, `android/app/src/main/java/com/aura/music/data/network/AuraApiDtos.kt`, `android/app/src/main/java/com/aura/music/data/network/AuraApiService.kt`, `android/app/src/main/java/com/aura/music/data/repository/DownloadRepository.kt`, `android/app/src/main/java/com/aura/music/ui/downloads/DownloadsViewModel.kt`, `android/app/src/main/java/com/aura/music/ui/screens/LibraryAndDetailsScreens.kt`, `android/app/src/main/java/com/aura/music/ui/screens/SettingsScreen.kt`, `BUILD.md` | Implémentation du flux de secours interactif fuzzy matching < 75% (5 propositions YTM, suspension de job en requires_resolution et endpoint de résolution directe) et de l'extraction automatique sécurisée de cookies via WebView YouTube vers Netscape.
- 2026-05-29T02:01:00+02:00 | code, api | `server/app/api/routes/downloads.py`, `BUILD.md` | Résolution d'un bug serveur (NameError: DOWNLOADS_DIR is not defined) lors de l'appel à la route `GET /downloads/{job_id}/file` en important proprement la constante depuis `download_service.py`.
- 2026-05-29T01:53:00+02:00 | code, ui | `android/app/src/main/java/com/aura/music/data/repository/DownloadRepository.kt`, `android/app/src/main/java/com/aura/music/ui/screens/SettingsScreen.kt`, `android/app/src/main/java/com/aura/music/ui/AuraApp.kt`, `BUILD.md` | Intégration de l'interface de contournement de blocage YouTube. Ajout de la méthode `uploadCookies` dans `DownloadRepository` reliée à l'API, création de la carte d'édition de cookies Netscape dans `SettingsScreen`, et raccordement de la navigation dans `AuraApp.kt`.
- 2026-05-29T01:44:00+02:00 | code | `android/app/src/main/java/com/aura/music/data/network/AuraApiService.kt`, `android/app/src/main/java/com/aura/music/data/network/AuraApiDtos.kt`, `android/app/src/main/java/com/aura/music/ui/screens/LibraryAndDetailsScreens.kt`, `android/app/src/main/java/com/aura/music/ui/screens/ScreenSharedComponents.kt`, `BUILD.md` | Résolution de la panne générale de compilation : ajout de la déclaration de package manquante dans `AuraApiService.kt`, correction de la syntaxe/duplication Moshi dans `AuraApiDtos.kt`, et ajout des imports manquants pour `Icons.Rounded.Refresh` et `Icons.Rounded.Downloading` dans les fichiers d'interface graphique.
- 2026-05-29T01:25:00+02:00 | code, docs, ui | `android/app/src/main/java/com/aura/music/data/network/AuraApiService.kt`, `android/app/src/main/java/com/aura/music/data/local/LocalEntities.kt`, `android/app/src/main/java/com/aura/music/data/local/AuraDaos.kt`, `android/app/src/main/java/com/aura/music/data/repository/DownloadRepository.kt`, `android/app/src/main/java/com/aura/music/ui/downloads/DownloadsViewModel.kt`, `android/app/src/main/java/com/aura/music/core/AuraAppContainer.kt`, `android/app/src/main/java/com/aura/music/ui/screens/LibraryAndDetailsScreens.kt`, `android/app/src/main/java/com/aura/music/ui/AuraApp.kt`, `android/app/src/main/java/com/aura/music/ui/screens/ScreenSharedComponents.kt`, `android/app/src/main/java/com/aura/music/ui/screens/SearchScreen.kt`, `docs/android/screens/downloads.md`, `docs/android/screens/downloads-layout.md`, `BUILD.md` | AND-007 : Implémentation complète de l'intégration client Android pour les téléchargements asynchrones. Support streaming MP3 rétro-compatible dans `context.filesDir/downloads/` préservant les données lors des mises à jour, interface réactive avec barre de progression `LinearProgressIndicator` (Blaze orange), bouton de rafraîchissement TopBar, et option contextuelle "Télécharger" intégrée sur les résultats de recherche en ligne.
- 2026-05-29T00:35:00+02:00 | code, docs, api | `server/app/services/download_test_service.py`, `server/app/api/routes/test_download.py`, `infra/docker-compose.vps.yml`, `infra/docker/server.Dockerfile`, `BUILD.md` | SRV-006 : validation de la faisabilité du téléchargement yt-dlp sur le VPS Contabo avec intégration de Deno, routage IPv6, PO Token provider (jim60105/bgutil-pot) et cookies Netscape. Fusion sur master.
- 2026-05-28T01:32:14+02:00 | code, docs, ui | `android/app/src/main/java/com/aura/music/ui/screens/SearchScreen.kt`, `android/app/src/main/java/com/aura/music/ui/search/SearchViewModel.kt`, `android/app/src/main/java/com/aura/music/ui/screens/HybridDetailScreens.kt`, `docs/android/screens/artist-layout.md`, `docs/android/screens/search.md`, `BUILD.md` | AND-011 : page artiste online sans degrade, sorties online triees et separees albums/singles, actions artiste retirees, titre top bar artiste masque et retour Search conservant l'onglet avec refresh local.

- 2026-05-28T01:42:30+02:00 | code, docs, api | `server/app/schemas/responses.py`, `server/app/api/routes/search.py`, `server/app/api/routes/artists.py`, `server/app/api/routes/albums.py`, `server/app/services/resolve_service.py`, `android/app/src/main/java/com/aura/music/data/network/AuraApiDtos.kt`, `android/app/src/main/java/com/aura/music/ui/screens/HybridDetailScreens.kt`, `docs/server/api-contract.md`, `docs/android/screens/artist-layout.md`, `docs/android/screens/album.md`, `BUILD.md` | AND-011 : ajout de `release_type` aux payloads album backend/Android et separation Albums/Singles basee sur cette donnee canonique avec fallback visuel sur `track_count`.
- 2026-05-28T01:05:42+02:00 | code, ui | `android/app/src/main/java/com/aura/music/ui/screens/SearchScreen.kt`, `android/app/src/main/java/com/aura/music/ui/screens/ScreenSharedComponents.kt`, `BUILD.md` | AND-011 : correction des cartes artiste/album local et online avec hauteur suffisante pour les metadonnees et affichage des images online depuis `pictureUri` / `coverUri`.
- 2026-04-30T00:00:00+02:00 | code, ui, refactor | `android/app/src/main/java/com/aura/music/ui/screens/SearchScreen.kt`, `android/app/src/main/java/com/aura/music/ui/screens/ScreenSharedComponents.kt`, `BUILD.md` | AND-011 (Affinage UI) : Suppression totale de la section "Meilleur résultat" (Best Match) de l'écran de recherche, ainsi que du composant `SharedHeroCard`, suite à des problèmes de layout insolubles à court terme (images non affichées pour l'online, sous-textes coupés). Simplification de l'affichage en gardant uniquement les rails (listes horizontales).
- 2026-04-26T12:30:00+02:00 | code, ui, refactor | `android/app/src/main/java/com/aura/music/ui/screens/SearchScreen.kt`, `android/app/src/main/java/com/aura/music/ui/screens/HybridDetailScreens.kt`, `android/app/src/main/java/com/aura/music/ui/search/SearchViewModel.kt`, `android/app/src/main/java/com/aura/music/core/AuraAppContainer.kt` | AND-011 (Affinage UI) : Uniformisation des layout Hero Cards en Row avec images, ajout appel asynchrone pour enrichir le Meilleur Résultat local s'il manque d'image, pagination avec bouton "Afficher tous les titres" pour la liste de top titres artiste en ligne, affichage de la liste de titres de l'album en ligne si local vide.
- 2026-04-25T14:45:00+02:00 | code, schema, refactor | `android/app/src/main/java/com/aura/music/data/repository/SearchRepository.kt`, `android/app/src/main/java/com/aura/music/ui/search/SearchViewModel.kt`, `android/app/src/main/java/com/aura/music/ui/screens/SearchScreen.kt`, `android/app/src/main/java/com/aura/music/ui/screens/HybridDetailViewModels.kt`, `android/app/src/main/java/com/aura/music/ui/screens/HybridDetailScreens.kt`, `android/app/src/main/java/com/aura/music/data/local/AuraDatabase.kt` | Cloture reelle de la Phase 3 : SearchViewModel consomme les reglages reels et le context, ecrans hybrides completent le support online-first via IDs opaques (sans entree locale prealable) avec spinner correct, et ajout de la vraie migration Room v1->v2 (non destructive) pour les schemas de source links.
- 2026-04-25T14:30:00+02:00 | code, schema, api | `server/app/services/resolve_service.py`, `server/app/api/routes/resolve.py`, `server/app/api/router.py`, `android/app/src/main/java/com/aura/music/data/local/LocalEntities.kt`, `android/app/src/main/java/com/aura/music/data/local/AuraDaos.kt`, `android/app/src/main/java/com/aura/music/data/local/AuraDatabase.kt`, `android/app/src/main/java/com/aura/music/data/network/AuraApiService.kt`, `android/app/src/main/java/com/aura/music/data/network/AuraApiDtos.kt`, `android/app/src/main/java/com/aura/music/data/network/NetworkPolicyChecker.kt`, `android/app/src/main/java/com/aura/music/data/repository/EnrichmentRepository.kt`, `android/app/src/main/java/com/aura/music/data/repository/SearchRepository.kt`, `android/app/src/main/java/com/aura/music/core/AuraAppContainer.kt`, `android/app/src/main/java/com/aura/music/ui/screens/HybridDetailViewModels.kt`, `android/app/src/main/java/com/aura/music/ui/screens/HybridDetailScreens.kt`, `android/app/src/main/java/com/aura/music/ui/AuraApp.kt`, `BUILD.md` | SRV-008 : endpoints resolve backend + ResolveService scores textuels ; AND-009 : NetworkPolicyChecker + EnrichmentRepository (TTL 7j, persistance Room) + SearchRepository hybridSearch gate reseau ; AND-010 : HybridArtistScreen + HybridAlbumScreen + ViewModels local-first + enrichissement async ; schema Room v2 (ArtistSourceLinkEntity, AlbumSourceLinkEntity, colonnes artwork_origin/artwork_last_resolved_at).
- 2026-04-25T12:40:57+02:00 | docs, schema, api, decision | `docs/android/screens/settings.md`, `docs/android/screens/search.md`, `docs/android/screens/artist.md`, `docs/android/screens/album.md`, `docs/android/screens/artist-layout.md`, `docs/android/screens/album-layout.md`, `docs/android/local-persistence.md`, `docs/android/room-schema.md`, `docs/domain/entities.md`, `docs/product/user-flows.md`, `docs/server/api-contract.md`, `docs/server/api-sync-flows.md`, `docs/server/providers/deezer.md`, `BUILD.md` | formalisation de la gouvernance reseau et de l'enrichissement metadata : les reglages `Settings` deviennent normatifs, les images locales doivent etre persistees en `Room`, des mappings `artist` / `album` sont ajoutes au schema Android et un lot backend `SRV-008` est introduit pour la resolution metadata et les details hero-ready.
- 2026-04-23T18:53:14+02:00 | code, docs, api | `android/app/src/main/java/com/aura/music/data/repository/SearchRepository.kt`, `server/app/providers/deezer/adapter.py`, `docs/android/screens/search.md`, `docs/android/screens/search-layout.md`, `docs/product/user-flows.md`, `docs/server/api-contract.md`, `BUILD.md` | la selection de `Meilleur resultat` est maintenant documentee et alignee : le backend choisit `best_match` parmi tracks/artists/albums par score textuel, et Android arbitre le hero final en donnant la priorite a un match local fort.
- 2026-04-23T17:54:48+02:00 | code, docs, api | `server/app/providers/deezer/client.py`, `server/app/providers/deezer/adapter.py`, `server/app/domain/models.py`, `server/app/api/routes/search.py`, `docs/android/screens/search.md`, `docs/android/screens/search-layout.md`, `docs/android/ui/component-states.md`, `BUILD.md` | correction de la recherche online hybride : le backend alimente maintenant `artists` et `albums` via des recherches Deezer dediees, `best_match` peut representer track/artist/album, et la doc Search officialise les onglets Bibliotheque/En ligne ainsi que l'exclusion des playlists du perimetre.
- 2026-04-23T14:30:00+02:00 | code | `android/app/src/main/java/com/aura/music/ui/screens/SearchScreen.kt`, `android/app/src/main/java/com/aura/music/ui/screens/ScreenSharedComponents.kt`, `android/app/src/main/java/com/aura/music/ui/search/SearchViewModel.kt`, `android/app/src/main/java/com/aura/music/data/repository/SearchRepository.kt`, `BUILD.md` | AND-005 refonte UI recherche : TabRow sticky Bibliothèque/En ligne, LocalLibrarySearchTab avec like/unlike + playlist (contextType='standard'), OnlineSearchTab avec playlist seulement (contextType='search_online'), suppression 3 doublons SharedTrackRowItem/BrowseArtist/AlbumRail, ajout likeLocalTrack() ViewModel et toggleLike() Repository. NB: résultats online affichent titres uniquement, artistes/albums online non implémentés pour l'instant.
- 2026-04-19T12:21:00+02:00 | code, api | `android/app/src/main/AndroidManifest.xml`, `android/app/build.gradle.kts`, `android/app/src/main/java/com/aura/music/data/network/BestMatchAdapter.kt`, `android/app/src/main/java/com/aura/music/data/network/AuraApiDtos.kt`, `android/app/src/main/java/com/aura/music/data/network/AuraHttpClientFactory.kt`, `android/app/src/main/java/com/aura/music/data/repository/SearchRepository.kt`, `BUILD.md` | AND-005 : ajout des permissions réseau et du `usesCleartextTraffic`, intégration de `moshi-adapters` et création de `BestMatchAdapter` pour parser l'objet polymorphe API correctement, renvoi de `onlineError` vers l'UI ; migration HTTPS repoussée à SRV-003.
- 2026-04-18T23:37:59+02:00 | code, docs, api | `server/app/core/aura_id_codec.py`, `server/app/api/routes/search.py`, `server/app/api/routes/artists.py`, `server/app/api/routes/albums.py`, `server/app/providers/deezer/client.py`, `server/app/providers/deezer/adapter.py`, `server/app/services/artist_service.py`, `server/app/services/album_service.py`, `server/app/schemas/responses.py`, `docs/server/api-contract.md`, `BUILD.md` | SRV-002 complete dans le repo : reponses alignees sur des IDs backend opaques resolus cote serveur, details `Artist` et `Album` chainables depuis `Search`, envelope d'erreur canonique et handoff explicite ajoute pour `AND-005`.
- 2026-04-18T23:22:07+02:00 | code, api | `server/app/api/router.py`, `server/app/api/routes/health.py`, `server/app/api/routes/search.py`, `server/app/api/routes/artists.py`, `server/app/api/routes/albums.py`, `server/app/services/search_service.py`, `server/app/services/artist_service.py`, `server/app/services/album_service.py`, `server/app/providers/deezer/*`, `server/app/schemas/responses.py`, `server/app/core/id_generator.py`, `server/app/db/migrations/001_create_catalog_tables.sql`, `server/app/db/migrations/002_create_mapping_tables.sql`, `BUILD.md` | SRV-002 avance : endpoints publics actifs et deployes sur VPS avec enveloppe canonique, adapter Deezer et structure catalogue/mappings ; reste a remplacer les `provider_id` exposes par des IDs AURA backend opaques dans les payloads.
- 2026-04-18T17:38:42+02:00 | docs, schema, decision | `docs/domain/entities.md`, `docs/android/room-schema.md`, `docs/server/api-contract.md`, `docs/server/database-postgres.md`, `docs/server/sync-conflict-resolution.md`, `BUILD.md` | clarification de la strategie d'identite AURA : IDs backend opaques en `TEXT`, IDs locaux `MediaStore` gardes cote Android, matching local/online via mappings avant implementation de `SRV-002` et `SRV-004`.
- 2026-04-18T15:30:00+02:00 | code, docs | `android/app/src/main/java/com/aura/music/ui/screens/SearchScreen.kt`, `android/app/src/main/java/com/aura/music/ui/screens/LibraryAndDetailsScreens.kt`, `android/app/src/main/java/com/aura/music/ui/screens/ScreenSharedComponents.kt`, `android/app/src/main/java/com/aura/music/ui/screens/PlaylistDetailScreenNew.kt`, `android/app/src/main/java/com/aura/music/ui/AuraApp.kt`, `docs/android/screens/album-layout.md`, `docs/android/ui/component-states.md`, `BUILD.md` | AND-007 milestone : refactorisation complete du menu contextuel TrackRow avec variantes par contexte (standard/playlist/favorites), artiste cliquable dans AlbumScreen, test button recherche avec album reel au lieu d'ID fictif, suppression bouton Artist et titre Tracklist.
- 2026-04-17T22:59:00+02:00 | code, docs | `android/app/build.gradle.kts`, `android/app/src/main/java/com/aura/music/ui/screens/PlayerScreen.kt`, `android/app/src/main/java/com/aura/music/data/player/QueueManager.kt`, `android/app/src/main/java/com/aura/music/domain/player/PlaybackOrchestrator.kt`, `android/app/src/main/java/com/aura/music/domain/player/PlayerEvent.kt`, `docs/android/screens/player-layout.md`, `BUILD.md` | AND-007 milestones : intégration de burnoutcrew.composereorderable pour supporter le drag and drop complet et indépendant sur la Priority Queue locale et la Main Queue (À suivre). Résolution des bugs de duplication d'ID internes et décalage d'offset lié à "spacedBy".
- 2026-04-17T20:15:00+02:00 | code, docs | `android/app/build.gradle.kts`, `android/app/src/main/java/com/aura/music/data/media/MediaStoreAudioDataSource.kt`, `android/app/src/main/java/com/aura/music/data/repository/LocalLibraryRepository.kt`, `android/app/src/main/java/com/aura/music/data/local/LocalEntities.kt`, `android/app/src/main/java/com/aura/music/data/local/AuraDaos.kt`, `android/app/src/main/java/com/aura/music/ui/screens/ScreenSharedComponents.kt`, `android/app/src/main/java/com/aura/music/ui/screens/LibraryAndDetailsScreens.kt`, `android/app/src/main/java/com/aura/music/ui/screens/PlaylistDetailScreenNew.kt`, `android/app/src/main/java/com/aura/music/ui/screens/HomeScreen.kt`, `docs/android/screens/favorites-layout.md`, `docs/android/screens/playlists-layout.md`, `BUILD.md` | AND-007 milestones : implémentation de la vue Favoris (layout canonique et listage depuis la database). Refonte du design des listes de lecture TrackRow pour intégrer les covers d'album en remplacement de l'icône statique Play/Favorite, grâce à l'apport de `io.coil-kt:coil-compose` et à l'extraction de l'URI d'album de l'API MediaStore interne.
- 2026-04-17T12:00:00+02:00 | code, docs | `android/app/src/main/java/com/aura/music/ui/screens/PlaylistsListScreen.kt`, `android/app/src/main/java/com/aura/music/ui/screens/PlaylistDetailScreenNew.kt`, `android/app/src/main/java/com/aura/music/ui/AuraApp.kt`, `android/app/src/main/java/com/aura/music/ui/screens/LibraryAndDetailsScreens.kt`, `docs/android/ui/component-states.md`, `docs/android/screens/playlists-layout.md`, `BUILD.md` | AND-007 milestones : refonte UI Playlists avec PlaylistsListScreen et PlaylistDetailScreenNew selon specification (hero card creation, playlist rows, action bar play/shuffle/menu, track rows contextuels), routes AuraApp routees vers nouveaux screens, dialogues (PlaylistNameDialog, ConfirmDialog, toTrackListRow) rendus publics, clarification etats TrackRow avec menu contextuel variant par contexte (standard/playlist/queue).
- 2026-04-16T00:26:14+02:00 | code | `android/app/src/main/java/com/aura/music/ui/AuraApp.kt`, `android/app/src/main/java/com/aura/music/ui/screens/LibraryAndDetailsScreens.kt`, `BUILD.md` | harmonisation des `contentDescription` en francais pour les actions de navigation et de transport (retour, piste precedente/suivante, lecture/pause, reorder/suppression).
- 2026-04-16T00:11:21+02:00 | docs | `docs/android/ui/components.md`, `docs/android/ui/component-states.md`, `docs/android/ui/design-system.md`, `docs/android/ui/screen-composition.md`, `BUILD.md` | harmonisation terminologique UI avec lexique canonique, clarification Card vs Rail et alignement des mappings code vers `ScreenSharedComponents.kt`.
- 2026-04-16T00:04:02+02:00 | docs | `docs/android/ui/components.md`, `docs/android/ui/component-states.md`, `BUILD.md` | clarification du mapping des composants UI reutilisables avec centralisation des briques partagees dans `ScreenSharedComponents.kt` et alignement des references d'etats UI.
- 2026-04-11T14:45:00+02:00 | code, docs | `android/app/src/main/java/com/aura/music/ui/theme/Color.kt`, `android/app/src/main/java/com/aura/music/ui/theme/Type.kt`, `android/app/src/main/java/com/aura/music/ui/theme/Theme.kt`, `android/app/src/main/res/font/outfit_*.ttf`, `android/app/src/main/java/com/aura/music/ui/AuraApp.kt`, `android/app/src/main/java/com/aura/music/ui/screens/HomeScreen.kt`, `android/app/src/main/java/com/aura/music/ui/screens/LibraryAndDetailsScreens.kt`, `android/app/src/main/java/com/aura/music/data/local/AuraDaos.kt`, `android/app/src/main/java/com/aura/music/data/repository/LocalLibraryRepository.kt`, `docs/android/screens/home-layout.md`, `docs/android/screens/library-layout.md`, `docs/android/screens/home.md`, `docs/android/screens/library.md`, `BUILD.md` | AND-008 complete : mise en place du design system AuraTheme (Color.kt, Type.kt, Theme.kt, police Outfit), refonte HomeScreen et LibraryScreen selon layouts valides, NavBar globale 4 onglets (Home, Search, Library, Settings), RouteScaffold enrichi avec actions et style personnalisable, recherche locale filtree artistes/albums (DAO + Repository), suppression doublons titres.
- 2026-04-11T11:35:00+02:00 | code | `android/app/src/main/java/com/aura/music/data/local/AuraDaos.kt`, `android/app/src/main/java/com/aura/music/data/local/LocalEntities.kt`, `android/app/src/main/java/com/aura/music/data/repository/LocalLibraryRepository.kt`, `android/app/src/main/java/com/aura/music/ui/AuraApp.kt`, `android/app/src/main/java/com/aura/music/ui/screens/HomeScreen.kt`, `android/app/src/main/java/com/aura/music/ui/screens/LibraryAndDetailsScreens.kt`, `BUILD.md` | correction du flux Add track pour exposer toute la bibliotheque locale et implementation avancee de AND-007 avec Home, Library, Artist, Album, Downloads, Settings et Player enrichis.
- 2026-04-10T13:15:00+02:00 | code, docs | `android/app/src/main/java/com/aura/music/data/local/AuraDaos.kt`, `android/app/src/main/java/com/aura/music/data/local/LocalEntities.kt`, `android/app/src/main/java/com/aura/music/data/repository/LocalLibraryRepository.kt`, `android/app/src/main/java/com/aura/music/ui/AuraApp.kt`, `android/app/src/main/java/com/aura/music/ui/screens/LibraryAndDetailsScreens.kt`, `docs/android/screens/playlists.md`, `docs/ops/hosting-strategy.md`, `BUILD.md` | implementation de AND-006 avec playlists locales utilisables, lecture contexte playlist, CRUD, ajout de pistes locales et reordonnancement simple, plus validation du backend Contabo.
- 2026-04-10T12:30:00+02:00 | code, docs, decision | `infra/docker-compose.vps.yml`, `infra/docker-compose.vps.caddy.yml`, `infra/caddy/Caddyfile.example`, `infra/vps/README.md`, `docs/ops/hosting-strategy.md`, `BUILD.md` | choix Contabo VPS 10, ajout du chemin de deploiement Docker sur VPS et du passage optionnel a Caddy/TLS.
- 2026-04-10T12:00:00+02:00 | docs, decision | `docs/android/ui/design-system.md`, `docs/android/ui/components.md`, `docs/android/ui/component-states.md`, `docs/android/ui/screen-composition.md`, `docs/adrs/006-online-search-backend-only.md`, `docs/ops/hosting-strategy.md`, `docs/server/architecture.md`, `docs/server/api-contract.md`, `BUILD.md` | consolidation DA complete, validation backend-only pour la recherche online et ajout de la phase 2.5 de clarification hebergement.
- 2026-04-03T12:28:00+02:00 | code, docs | `android/app/src/main/java/com/aura/music/domain/player/PlaybackOrchestrator.kt`, `android/app/src/main/java/com/aura/music/ui/player/PlayerViewModel.kt`, `android/app/src/main/java/com/aura/music/ui/AuraApp.kt`, `android/app/src/main/java/com/aura/music/data/repository/LocalLibraryRepository.kt`, `docs/android/player/queue-rules.md` | resolution des bugs de lecture locale (flickering via suppression du SeekTo repetitif, correctif navigation playlist via passe explicite de toutes les listes UI au context, et retablissement resume complet du snapshot).
- 2026-04-02T21:44:00+02:00 | code, docs | `android/app/build.gradle.kts`, `android/app/src/main/AndroidManifest.xml`, `android/app/src/main/java/com/aura/music/domain/player/*`, `android/app/src/main/java/com/aura/music/data/player/*`, `android/app/src/main/java/com/aura/music/service/PlaybackService.kt`, `android/app/src/main/java/com/aura/music/ui/player/PlayerViewModel.kt`, `android/app/src/main/java/com/aura/music/ui/AuraApp.kt`, `android/app/src/main/java/com/aura/music/ui/screens/LibraryAndDetailsScreens.kt`, `android/app/src/main/java/com/aura/music/core/AuraAppContainer.kt`, `android/app/src/main/java/com/aura/music/AuraApplication.kt`, `docs/android/player/architecture.md`, `docs/android/player/queue-rules.md`, `docs/android/app-architecture.md`, `docs/android/navigation.md`, `docs/android/local-persistence.md`, `docs/android/room-schema.md`, `BUILD.md` | implementation de AND-004 avec moteur Media3, QueueManager, PlaybackOrchestrator, PlaybackStateStore, PlayerViewModel et ecran Player minimal. Comblement de la dette documentaire AND-002 et AND-003 avec sections Code Mapping.
- 2026-04-02T20:35:00+02:00 | code | `android/app/build.gradle.kts`, `android/app/src/main/AndroidManifest.xml`, `android/app/src/main/java/com/aura/music/*`, `BUILD.md` | implementation de AND-002 et AND-003 avec navigation Compose, shell multi-ecrans, Room et integration MediaStore.
- 2026-04-02T19:05:53+02:00 | code | `android/gradlew`, `android/gradlew.bat`, `android/gradle/wrapper/*`, `render.yaml`, `server/.env.example`, `server/app/config.py`, `infra/*`, `BUILD.md` | ajout du wrapper Gradle, du blueprint Render racine et de la configuration Qdrant avec cle API.
- 2026-04-02T19:05:53+02:00 | code | `android/*`, `server/*`, `infra/*`, `.gitignore`, `BUILD.md` | creation du socle monorepo Android, FastAPI et infra avec premiere base executable.
- 2026-04-02T18:53:54+02:00 | docs | `BUILD.md`, `llms-full.txt` | ajout de la trajectoire globale priorisee pour guider l'ordre d'implementation et eviter la dispersion.
- 2026-04-02T18:50:43+02:00 | docs | `docs/server/sync-batch-api.md`, `docs/server/api-contract.md`, `docs/server/sync-conflict-resolution.md`, `docs/README.md`, `llms.txt`, `llms-full.txt`, `BUILD.md` | ajout des contrats API batch concrets pour bootstrap, push et pull de sync.
- 2026-04-02T18:42:39+02:00 | docs | `docs/server/sync-conflict-resolution.md`, `docs/README.md`, `llms.txt`, `llms-full.txt` | ajout de la strategie canonique de resolution des conflits de sync avec payloads exacts par entite.
- 2026-04-02T18:35:05+02:00 | docs | `BUILD.md`, `docs/README.md`, `llms.txt`, `llms-full.txt` | ajout du fichier de pilotage commun avec regles strictes, board de code et journal horodate.
- 2026-04-02T18:20:00+02:00 | docs | `docs/server/api-sync-flows.md`, `docs/README.md`, `llms.txt`, `llms-full.txt` | ajout des diagrammes de flux entre Android, API, Supabase, Qdrant et jobs.
- 2026-04-02T18:10:00+02:00 | docs | `docs/domain/data-relationships.md`, `docs/android/room-relationships.md`, `docs/server/postgres-relationships.md`, `docs/server/api-contract.md`, `docs/README.md`, `llms.txt`, `llms-full.txt` | ajout des diagrammes ER et refonte du contrat API.
- 2026-04-02T17:50:00+02:00 | docs | `docs/product/*`, `docs/domain/*`, `docs/android/*`, `docs/server/*`, `llms.txt`, `llms-full.txt` | base documentaire initiale consolidee et versionnee.
