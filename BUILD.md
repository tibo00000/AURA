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
- finaliser la couche produit visible cote Android

Priorite :
- `SRV-002`
- `AND-005`

Resultat attendu :
- recherche online disponible cote backend
- fusion local + online cote Android
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

## Code Work Board

### Android
| ID | Area | Work Item | Status | Dependencies | Canonical Docs | Notes |
|---|---|---|---|---|---|---|
| AND-001 | android | Initialiser le projet Android Kotlin avec structure de modules minimale | completed | none | `docs/adrs/002-android-native-client.md`, `docs/android/app-architecture.md` | squelette Compose, structure Gradle et wrapper poses |
| AND-002 | android | Mettre en place la navigation Compose et le shell applicatif | completed | AND-001 | `docs/android/navigation.md`, `docs/product/navigation.md` | graphe principal, surfaces detail et mini-player shell poses |
| AND-003 | android | Implementer la couche locale `Room` et l'integration `MediaStore` | completed | AND-001 | `docs/android/local-persistence.md`, `docs/android/room-schema.md`, `docs/android/room-relationships.md` | base Room, import local MediaStore et settings par defaut poses |
| AND-004 | android | Implementer le moteur player Media3 et les regles de queue | completed | AND-001 | `docs/android/player/architecture.md`, `docs/android/player/queue-rules.md`, `docs/domain/playback-model.md` | priority queue en memoire, PlaybackService + QueueManager + Orchestrator + PlayerViewModel poses, ecran Player minimal fonctionnel |
| AND-005 | android | Implementer l'ecran `Search` avec orchestration local + online | not_started | AND-002, AND-003 | `docs/android/screens/search.md`, `docs/product/user-flows.md`, `docs/server/api-contract.md` | fusion faite cote Android |
| AND-006 | android | Implementer la gestion des playlists locales et leur UI | not_started | AND-002, AND-003 | `docs/android/screens/playlists.md`, `docs/product/user-flows.md` | create, rename, delete, reorder |
| AND-007 | android | Implementer les ecrans `Artist`, `Album`, `Home`, `Library`, `Downloads`, `Settings`, `Player` complet | not_started | AND-002, AND-003, AND-004 | `docs/android/screens/artist.md`, `docs/android/screens/album.md`, `docs/android/screens/home.md`, `docs/android/screens/library.md`, `docs/android/screens/downloads.md`, `docs/android/screens/settings.md`, `docs/android/screens/player.md`, `docs/android/screens/player-layout.md` | detail des surfaces, ecran Player complet avec seek interactif, vue queue et layout avance |

### Backend
| ID | Area | Work Item | Status | Dependencies | Canonical Docs | Notes |
|---|---|---|---|---|---|---|
| SRV-001 | backend | Initialiser le projet FastAPI avec structure applicative minimale | completed | none | `docs/adrs/003-backend-fastapi-supabase-qdrant.md`, `docs/server/architecture.md` | base HTTP, config et `/health` poses |
| SRV-002 | backend | Implementer les endpoints online publics `health`, `search`, `artist`, `album` | not_started | SRV-001 | `docs/server/api-contract.md`, `docs/server/providers/deezer.md` | online only |
| SRV-003 | backend | Implementer les endpoints de sync cloud optionnelle `/me/...` | not_started | SRV-001 | `docs/server/api-contract.md`, `docs/server/database-postgres.md` | sync user-scoped |
| SRV-007 | backend | Implementer les endpoints batch `bootstrap`, `push-batch`, `pull-batch` pour la sync | not_started | SRV-001, SRV-004 | `docs/server/sync-conflict-resolution.md`, `docs/server/sync-batch-api.md` | transport canonique de sync |
| SRV-004 | backend | Implementer les tables et acces `Supabase / Postgres` | not_started | SRV-001 | `docs/server/database-postgres.md`, `docs/server/postgres-relationships.md` | modele cloud |
| SRV-005 | backend | Integrer `Qdrant` pour la recherche vectorielle et les mappings piste | not_started | SRV-001 | `docs/server/vector-search-qdrant.md`, `docs/server/api-sync-flows.md` | vecteurs plus payload |
| SRV-006 | backend | Implementer le systeme de jobs et l'API downloads generique | not_started | SRV-001, SRV-004 | `docs/server/jobs.md`, `docs/server/api-contract.md`, `docs/server/api-sync-flows.md` | source de download encore opaque |

### Infrastructure et gouvernance
| ID | Area | Work Item | Status | Dependencies | Canonical Docs | Notes |
|---|---|---|---|---|---|---|
| INF-001 | infra | Definir les variables d'environnement et fichiers d'exemple | completed | SRV-001 | `docs/ops/env-vars.md`, `docs/server/security-and-secrets.md` | `.env.example` backend et exemple Android ajoutes |
| INF-002 | infra | Definir Docker et l'environnement local de dev backend | completed | SRV-001 | `docs/adrs/003-backend-fastapi-supabase-qdrant.md`, `docs/server/architecture.md` | Dockerfile, compose local et blueprint Render racine ajoutes |
| INF-003 | infra | Fixer une cible backend always-on avant la recherche online produit | in_progress | INF-002 | `docs/ops/hosting-strategy.md`, `docs/server/architecture.md`, `docs/server/api-contract.md` | Contabo VPS 10 choisi, deploiement Docker a valider en live |
| GOV-001 | docs | Maintenir la coherence entre code, specs et index machine-friendly | in_progress | none | `docs/README.md`, `docs/documentation/style-guide.md`, `llms.txt`, `llms-full.txt` | activite continue |

## Done Before Code
| ID | Area | Work Item | Status | Dependencies | Canonical Docs | Notes |
|---|---|---|---|---|---|---|
| DOC-001 | docs | Poser la structure documentaire machine-friendly | completed | none | `docs/README.md`, `llms.txt`, `llms-full.txt` | base canonique en place |
| DOC-002 | docs | Documenter produit, navigation, user flows et ecrans | completed | DOC-001 | `docs/product/*`, `docs/android/screens/*` | socle UX documente |
| DOC-003 | docs | Documenter player, persistance, schemas et API | completed | DOC-001 | `docs/domain/*`, `docs/android/room-schema.md`, `docs/server/api-contract.md` | socle technique documente |
| DOC-004 | docs | Ajouter les diagrammes ER et les flux API orientes sync | completed | DOC-003 | `docs/domain/data-relationships.md`, `docs/android/room-relationships.md`, `docs/server/postgres-relationships.md`, `docs/server/api-sync-flows.md` | vues transverses disponibles |
| DOC-005 | docs | Consolider la DA complete et la strategie online backend-only | completed | DOC-002, DOC-003 | `docs/android/ui/*`, `docs/adrs/006-online-search-backend-only.md`, `docs/ops/hosting-strategy.md` | DA complete, backend-only search et hebergement always-on documentes |

## Journal des changements
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
