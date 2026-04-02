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
- Aucun code applicatif Android ou backend n'est encore present dans le depot.
- Le prochain objectif est d'utiliser ce fichier pour suivre l'implementation du code reel, brique par brique.

## Code Work Board

### Android
| ID | Area | Work Item | Status | Dependencies | Canonical Docs | Notes |
|---|---|---|---|---|---|---|
| AND-001 | android | Initialiser le projet Android Kotlin avec structure de modules minimale | not_started | none | `docs/adrs/002-android-native-client.md`, `docs/android/app-architecture.md` | point d'entree du code Android |
| AND-002 | android | Mettre en place la navigation Compose et le shell applicatif | not_started | AND-001 | `docs/android/navigation.md`, `docs/product/navigation.md` | bottom navigation et player entrypoint |
| AND-003 | android | Implementer la couche locale `Room` et l'integration `MediaStore` | not_started | AND-001 | `docs/android/local-persistence.md`, `docs/android/room-schema.md`, `docs/android/room-relationships.md` | persistance locale canonique |
| AND-004 | android | Implementer le moteur player Media3 et les regles de queue | not_started | AND-001 | `docs/android/player/architecture.md`, `docs/android/player/queue-rules.md`, `docs/domain/playback-model.md` | la priority queue reste non persistante |
| AND-005 | android | Implementer l'ecran `Search` avec orchestration local + online | not_started | AND-002, AND-003 | `docs/android/screens/search.md`, `docs/product/user-flows.md`, `docs/server/api-contract.md` | fusion faite cote Android |
| AND-006 | android | Implementer la gestion des playlists locales et leur UI | not_started | AND-002, AND-003 | `docs/android/screens/playlists.md`, `docs/product/user-flows.md` | create, rename, delete, reorder |
| AND-007 | android | Implementer les ecrans `Artist`, `Album`, `Home`, `Library`, `Downloads`, `Settings` | not_started | AND-002, AND-003 | `docs/android/screens/artist.md`, `docs/android/screens/album.md`, `docs/android/screens/home.md`, `docs/android/screens/library.md`, `docs/android/screens/downloads.md`, `docs/android/screens/settings.md` | detail des surfaces |

### Backend
| ID | Area | Work Item | Status | Dependencies | Canonical Docs | Notes |
|---|---|---|---|---|---|---|
| SRV-001 | backend | Initialiser le projet FastAPI avec structure applicative minimale | not_started | none | `docs/adrs/003-backend-fastapi-supabase-qdrant.md`, `docs/server/architecture.md` | base HTTP et configuration |
| SRV-002 | backend | Implementer les endpoints online publics `health`, `search`, `artist`, `album` | not_started | SRV-001 | `docs/server/api-contract.md`, `docs/server/providers/deezer.md` | online only |
| SRV-003 | backend | Implementer les endpoints de sync cloud optionnelle `/me/...` | not_started | SRV-001 | `docs/server/api-contract.md`, `docs/server/database-postgres.md` | sync user-scoped |
| SRV-004 | backend | Implementer les tables et acces `Supabase / Postgres` | not_started | SRV-001 | `docs/server/database-postgres.md`, `docs/server/postgres-relationships.md` | modele cloud |
| SRV-005 | backend | Integrer `Qdrant` pour la recherche vectorielle et les mappings piste | not_started | SRV-001 | `docs/server/vector-search-qdrant.md`, `docs/server/api-sync-flows.md` | vecteurs plus payload |
| SRV-006 | backend | Implementer le systeme de jobs et l'API downloads generique | not_started | SRV-001, SRV-004 | `docs/server/jobs.md`, `docs/server/api-contract.md`, `docs/server/api-sync-flows.md` | source de download encore opaque |

### Infrastructure et gouvernance
| ID | Area | Work Item | Status | Dependencies | Canonical Docs | Notes |
|---|---|---|---|---|---|---|
| INF-001 | infra | Definir les variables d'environnement et fichiers d'exemple | not_started | SRV-001 | `docs/ops/env-vars.md`, `docs/server/security-and-secrets.md` | Android et backend |
| INF-002 | infra | Definir Docker et l'environnement local de dev backend | not_started | SRV-001 | `docs/adrs/003-backend-fastapi-supabase-qdrant.md`, `docs/server/architecture.md` | environnement executable |
| GOV-001 | docs | Maintenir la coherence entre code, specs et index machine-friendly | in_progress | none | `docs/README.md`, `docs/documentation/style-guide.md`, `llms.txt`, `llms-full.txt` | activite continue |

## Done Before Code
| ID | Area | Work Item | Status | Dependencies | Canonical Docs | Notes |
|---|---|---|---|---|---|---|
| DOC-001 | docs | Poser la structure documentaire machine-friendly | completed | none | `docs/README.md`, `llms.txt`, `llms-full.txt` | base canonique en place |
| DOC-002 | docs | Documenter produit, navigation, user flows et ecrans | completed | DOC-001 | `docs/product/*`, `docs/android/screens/*` | socle UX documente |
| DOC-003 | docs | Documenter player, persistance, schemas et API | completed | DOC-001 | `docs/domain/*`, `docs/android/room-schema.md`, `docs/server/api-contract.md` | socle technique documente |
| DOC-004 | docs | Ajouter les diagrammes ER et les flux API orientes sync | completed | DOC-003 | `docs/domain/data-relationships.md`, `docs/android/room-relationships.md`, `docs/server/postgres-relationships.md`, `docs/server/api-sync-flows.md` | vues transverses disponibles |

## Journal des changements
- 2026-04-02T18:35:05+02:00 | docs | `BUILD.md`, `docs/README.md`, `llms.txt`, `llms-full.txt` | ajout du fichier de pilotage commun avec regles strictes, board de code et journal horodate.
- 2026-04-02T18:20:00+02:00 | docs | `docs/server/api-sync-flows.md`, `docs/README.md`, `llms.txt`, `llms-full.txt` | ajout des diagrammes de flux entre Android, API, Supabase, Qdrant et jobs.
- 2026-04-02T18:10:00+02:00 | docs | `docs/domain/data-relationships.md`, `docs/android/room-relationships.md`, `docs/server/postgres-relationships.md`, `docs/server/api-contract.md`, `docs/README.md`, `llms.txt`, `llms-full.txt` | ajout des diagrammes ER et refonte du contrat API.
- 2026-04-02T17:50:00+02:00 | docs | `docs/product/*`, `docs/domain/*`, `docs/android/*`, `docs/server/*`, `llms.txt`, `llms-full.txt` | base documentaire initiale consolidee et versionnee.
