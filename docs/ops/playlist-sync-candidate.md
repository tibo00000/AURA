# Candidat de synchronisation des playlists Aura

Branche `fix/playlist-sync-program`, issue du commit `03133655`.
Le chantier de démarrage Android et les protections de session intégrés
à ce commit font partie du point de comparaison. La version client reste
0.4.3, code 7, pendant la préparation du candidat.

| Lot | Commit | Périmètre |
| --- | --- | --- |
| A | `f14e98d1` | Snapshot PostgreSQL, réconciliation Room et outbox Android/Desktop |
| B | `2a824a8b` | Association et révision de source persistantes |
| D1 | `571e2ac6` | Intentions d’acquisition durables |
| C | `59dc7bff` | Actualisation incrémentale, exclusions et métadonnées |
| D | `17a276f0` | Traitement serveur et reprise des intentions |
| E | `eb4fc2a7` | Menu, bilans, file éligible et identifiants d’occurrence |
| F | Ce dossier | Tests croisés, inventaire et retour arrière |
| Suivi E/F | `c770f98f` | Répétition Media3 par occurrence et invalidation cloud au changement de compte |
| Suivi F | `60a549df` | Réutilisation des métadonnées normalisées dans l’index de recherche |
| Suivi C/F | `e4380932` | Refus des inventaires fournisseurs dont les pages sont discontinues |
| Suivi E/F | `aae00050` | Mise à jour des dossiers Media3 après vérification cloud |
| Suivi E | `7fb77fe4` | Suivi du bilan cloud après actualisation confirmée |

## Ordre de livraison proposé

1. Exporter et vérifier les tables de playlists, occurrences et opérations
   avant toute intervention. Conserver les bases Aura locales et le stockage
   audio sous leurs procédures de sauvegarde habituelles.
2. Appliquer sous autorisation distincte, dans l’ordre, les migrations
   `20261114120903_playlist_sync_snapshot.sql`,
   `20261114122131_playlist_sync_mutations.sql`,
   `20261114125746_playlist_source_association.sql`,
   `20261114181513_playlist_acquisition_intents.sql`,
   `20261114181653_playlist_refresh_and_exclusions.sql`,
   `20261114183022_playlist_acquisition_leases.sql` puis
   `20261114184616_playlist_playback_occurrence.sql`.
   Vérifier aussi la présence de la migration existante
   `005_add_download_job_operation_type.sql`.
3. Déployer le backend seul et vérifier les contrats historiques, le nouvel
   inventaire et les RPC en lecture avec un compte de validation. Garder
   `ENABLE_PLAYLIST_ACQUISITION_WORKER=false` et
   `ENABLE_STARTUP_RECOVERY=false` au premier contrôle.
4. Préparer les clients Android et Desktop, vérifier les migrations Room
   successives et l’aller-retour d’une playlist historique. L’activation
   fournisseur dépend des droits de lecture réels du compte et de
   l’application Aura. Relever le code de version minimal uniquement si
   des clients historiques doivent être retirés lors d’une release.
5. Activer le worker sur une seule instance d’exécution après la preuve de
   reprise et la revue des paramètres. Garder le manifeste d’autorisation
   vide tant que la provenance de chaque audio et les droits de
   téléchargement et de stockage ne sont pas validés.

## Contrats à vérifier

| Contrat | Changement et compatibilité |
| --- | --- |
| `GET /me/playlists/snapshot` | Inventaire cohérent, révisé et propre au compte, utilisé par Android et Desktop. L’inventaire historique garde son format. |
| `POST /me/sync/push-batch` | Les clients migrés envoient `playlist_sync_version=2` et lisent la révision acquittée par mutation. Les clients historiques conservent les mutations historiques. |
| `PUT /me/playlists/{id}/source` | Référence facultative et contrôle de révision. Le retrait utilise une référence nulle. |
| `POST /me/playlists/{id}/refresh` | Inventaire complet fourni par Android, révision de source attendue et identifiant stable au rejeu. Le bilan confirmé est disponible dans le snapshot. |
| `GET/POST /me/playlists/{id}/acquisitions` | Bilan groupé contrôlé par compte. POST admet des intentions, avec reprise ciblée facultative. La disponibilité est vérifiée dans le stockage. |
| `GET/PUT /me/playback-snapshot` et sync batch | Champ nullable `playlist_occurrence_id`. La restauration historique utilise le titre lorsqu’il identifie une occurrence unique. |
| `GET /me/sync/files` | Forme inchangée. Une entrée de métadonnées exige désormais un fichier audio présent et non vide. |
| Routes historiques de téléchargement | Les jobs nés d’une intention sont relancés depuis la playlist avec le manifeste. Les jobs indépendants gardent leur comportement antérieur. |

Les migrations Room 5 → 9 préservent les playlists existantes, les
mutations locales en attente et les liens audio locaux. Le code de
version minimal peut rester au seuil actuel si les vérifications de
compatibilité sur la cible confirment les contrats historiques.

## Retour arrière

En cas d’incident d’acquisition, couper d’abord le worker avec
`ENABLE_PLAYLIST_ACQUISITION_WORKER=false` et garder
`ENABLE_STARTUP_RECOVERY=false`. Laisser expirer les baux et conserver
les intentions, les jobs et les fichiers. Ce réglage suspend le
traitement. Les actualisations peuvent encore enregistrer des intentions.

Si le problème concerne le commit des playlists, suspendre également les
écritures client avant toute restauration. Privilégier un correctif en
avant qui conserve le snapshot et le contrat v2 si des clients migrés
sont déjà en service. Après la création de jobs playlist, le backend de
secours doit aussi conserver la vérification du champ
`download_jobs.playlist_acquisition` sur la relance historique. Le
backend antérieur au programme ne connaît ni cette protection ni le
contrat v2. Les schémas additifs sont conservés.

Les bases Room mises à niveau exigent une correction en avant ou une
restauration de sauvegarde explicite. Exporter les éditions locales et
l’outbox avant de restaurer une copie, puis réconcilier les événements
plus récents. Vérifier les comptes, nombres d’occurrences, références
fournisseur et jobs en attente avant de rouvrir la synchronisation.

## Inventaire avant nettoyage éventuel

Relever pour chaque playlist ses identifiants et positions, ses occurrences
répétées, les associations, les références de piste et les anomalies de
parent ou de position. Une occurrence sans fichier audio ou une playlist
sans lien fournisseur reste compatible. Une anomalie structurelle ne
justifie une suppression qu’après échec de réparation sur copie isolée,
export vérifié de la playlist, et autorisation explicite pour les IDs
concernés. L’inventaire de production et tout nettoyage sont des
interventions distinctes.

Exécuter `supabase/sql/playlist_sync_inventory.sql` avec `psql -X -v
ON_ERROR_STOP=1 -v owner_id=<UUID>` sur une copie ou après autorisation
de lecture. La requête isole le compte, compte les occurrences
volontaires et signale les positions non contiguës ainsi que les
références vides. Le résultat doit être joint à une sauvegarde des
occurrences et des références fournisseur pour toute décision de nettoyage.

## Rejeu local

- Gradle via le wrapper, tâches `:androidApp:testDebugUnitTest`,
  `:shared:testDebugUnitTest`, `:shared:desktopTest`,
  `:desktopApp:desktopTest` et `:androidApp:lintDebug`. Les tests
  de lecture couvrent la playlist mixte, la reprise Room et le changement
  de compte. Les fixtures Desktop couvrent la convergence de deux bases,
  les réordonnancements et les opérations non acquittées.
- Backend via `python -B -m pytest -p no:cacheprovider tests -q` dans
  `server`, avec configuration synthétique et stockage jetable.
- PostgreSQL et reprise via `python -B server/scripts/test_user_isolation_postgres.py --image postgres:17.11-alpine --download-cancellation --playlist-sync --playlist-worker-restart`.
  Le test exige les dépendances Python du backend. Il rejoue les sept
  migrations, les cinq scénarios playlist, 97 assertions d’isolation,
  10 assertions d’annulation et trois contrôles de rollback. Il lance
  deux processus séparés, interrompt le premier après l’écriture du job,
  rend l’intention à nouveau éligible, puis vérifie le même job à la
  deuxième tentative et le fichier synthétique servi par Aura. Le
  transport SQL du harnais utilise `psql` sur un conteneur sans réseau.

## Résultats obtenus

| Vérification | Résultat |
| --- | --- |
| Python | 399 réussites |
| Tests Android ciblés | 18 réussites, dont reprise Room et changement de compte |
| Rejeu ciblé Media3, file et contrôleurs | 21 réussites. ExoPlayer vérifie la position de reprise et la transition entre deux occurrences du même enregistrement en répétition. Les tests vérifient aussi le passage de l’identifiant dans le Bundle Media3, la perte d’une source et le changement de compte. |
| Rejeu ciblé de navigation Media3 | 17 réussites, dont la mise à jour du dossier après confirmation cloud, l’absence de notification sur un simple changement de progression et la pagination des grandes listes. |
| Bilan cloud Android | 10 tests ciblés réussis avec la lecture et la navigation. Le suivi reste actif pendant un traitement ou une reprise transitoire et s’arrête quand le statut est définitif ou requiert un choix. |
| Lecteurs Spotify/Deezer | 8 tests sur fixtures, dont une page sautée ou répétée avec total inchangé, une entrée Spotify malformée et une lecture Deezer complète sur deux pages. |
| Rejeu d’une actualisation Android | 11 tests ciblés avec migration Room et lecteurs fournisseur. Un accusé retardé acquitte la demande correspondante et préserve le bilan plus récent provenant d’un autre appareil. |
| Lint Android | 0 erreur, 51 avertissements restant au rapport. Deux diagnostics d’indentation corrigés. |
| Suites complètes Shared / Desktop | 12 tests Shared Android, 12 Shared Desktop et 30 Desktop réussis |
| Suite Android complète | 166 sur 166 au commit `7fb77fe4`. L’indexation des 50 000 titres prend 1,29 s sur ce rejeu, 2,68 s sur le rejeu précédent après optimisation et 5,31 s sur le commit de départ. Le seuil de 3 s est conservé. |
| PostgreSQL | Sept migrations rejouées, cinq scénarios playlist, tests d’isolation et d’annulation réussis. Inventaire synthétique et reprise dans deux processus vérifiés. |

Le test de coalescence PERF-FAV a également expiré lors d'un passage
antérieur sur les deux branches. Les mesures de cette section proviennent
de la machine de développement. La validation du démarrage, de la
lecture par le système et de la fluidité en défilement reste à faire sur
appareil.

## Preuves encore requises pour l’activation

- Parcours réels Android, Android Auto et reprise par le système, avec
  une playlist mixte contenant des doublons volontaires. Choisir la
  deuxième occurrence, changer de piste, tester les modes aléatoire et
  répétition, redémarrer puis contrôler l’occurrence et la position.
  Rejouer en mode avion et après déconnexion puis connexion à un autre
  compte. Tester aussi une playlist entièrement indisponible.
  Ouvrir dans Android Auto une playlist composée uniquement de titres
  cloud lors d’un démarrage sans cache, puis vérifier que le dossier
  affiche les titres quand le serveur confirme leur disponibilité.
- Mesures comparables de démarrage à froid et d’ouverture d’une grande
  playlist, sur le même appareil et avec le même corpus. Relever les
  temps jusqu’à l’affichage et au premier titre lisible ainsi que les
  images manquées en défilement.
- Validation des accès Spotify et Deezer avec la configuration Aura. En
  mode Développement, Spotify réserve la lecture du contenu aux playlists
  possédées par le compte ou sur lesquelles il collabore. Vérifier aussi
  l’attribution et les liens de retour des métadonnées et pochettes.
  [Guide Spotify](https://developer.spotify.com/documentation/web-api/tutorials/february-2026-migration-guide)
  [Politique Spotify](https://developer.spotify.com/policy)
- Vérification de la procédure de publication et des sources audio
  autorisées. Aucun test ne requiert de téléchargement de contenu tiers.
- Vérification sur un environnement de préproduction avec Supabase
  Auth et PostgREST. Le rejeu local simule uniquement le sujet du JWT.

Aucune révision de production, OTA ou base réelle n’entre dans la
validation locale. Chaque intervention distante doit faire l’objet d’une
validation distincte.
