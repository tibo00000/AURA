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
| F | `c2076823` | Tests croisés, inventaire et retour arrière |
| Suivi E/F | `c770f98f` | Répétition Media3 par occurrence et invalidation cloud au changement de compte |
| Suivi F | `60a549df` | Réutilisation des métadonnées normalisées dans l’index de recherche |
| Suivi C/F | `e4380932` | Refus des inventaires fournisseurs dont les pages sont discontinues |
| Suivi E/F | `aae00050` | Mise à jour des dossiers Media3 après vérification cloud |
| Suivi E | `7fb77fe4` | Suivi du bilan cloud après actualisation confirmée |
| Suivi C/F | `10b35096` | Acquittement des relectures sans régression du dernier bilan |
| Suivi D/F | `df6550d6`, `f675f725` | Vérification HTTP de la reprise et utilisation des métadonnées persistées pendant une panne Deezer |
| Suivi F | `1b544698` | Rejeu du contrat playlist avec Supabase Auth et PostgREST locaux |

Historique complet depuis le point de départ avec
`git log --oneline 03133655..fix/playlist-sync-program`.

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
   `20261114183022_playlist_acquisition_leases.sql`,
   `20261114184616_playlist_playback_occurrence.sql` puis
   `20261116223507_playlist_confirmed_exclusions.sql`.
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

La correction des exclusions remplace la fonction du déclencheur de retrait.
Elle s’applique aux retraits suivants et conserve les anciennes exclusions.
Si un retour arrière s’impose, restaurer la définition précédente de la
fonction via une migration ciblée avec les mêmes droits. Conserver les
exclusions enregistrées.

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
  Le test exige les dépendances Python du backend. Il rejoue les huit
  migrations, les cinq scénarios playlist, 97 assertions d’isolation,
  10 assertions d’annulation et trois contrôles de rollback. Il lance
  deux processus séparés, interrompt le premier après l’écriture du job,
  rend l’intention à nouveau éligible, puis vérifie le même job à la
  deuxième tentative et le fichier synthétique servi par Aura. Le
  bilan HTTP du propriétaire confirme la disponibilité, le second
  compte reçoit 404 et une requête authentifiée `Range` reçoit 206
  avec les octets du fichier de test via l’identifiant Aura ou son alias
  Deezer historique. Le catalogue Deezer est volontairement indisponible
  pendant les deux tentatives. Le transport SQL du harnais
  utilise `psql` sur un conteneur sans réseau. FastAPI vérifie des JWT
  signés pour le test, puis le harnais transmet le sujet à PostgreSQL.
- Ajouter `--playlist-supabase-http` à la même commande pour tester le
  transport Supabase d’Aura. Préparer les images locales
  `supabase/gotrue:v2.197.0`, `postgrest/postgrest:v14.18`, `caddy:2.8-alpine`
  et `aura-security-lot5:sec0052-20261023-amd64`. Les trois premières sont
  publiques. Pour le runtime Python, `--playlist-backend-image` accepte
  une autre image locale Linux amd64 dotée des dépendances de
  `server/pyproject.toml`. Le harnais monte le code candidat en lecture
  seule et partage uniquement la boucle locale du conteneur PostgreSQL,
  sans port publié. Caddy sert HTTPS sur HTTP/1.1 avec un certificat de
  test. Supabase Auth crée deux comptes synthétiques et signe leurs
  jetons. FastAPI vérifie ces jetons et
  exécute les RPC via le client Supabase d’Aura et PostgREST. Deux
  processus démarrent également le worker via le cycle de vie normal de
  FastAPI. Le premier est interrompu en cours de tentative, le second
  traite les intentions après expiration simulée des baux. Le worker
  utilise un manifeste synthétique et une fixture audio locale.

## Résultats obtenus

| Vérification | Résultat |
| --- | --- |
| Python | 400 réussites. Une panne temporaire de PostgREST avant le début du téléchargement laisse le job de playlist récupérable. |
| Tests Android ciblés | 18 réussites, dont reprise Room et changement de compte |
| Rejeu ciblé Media3, file et contrôleurs | 21 réussites. ExoPlayer vérifie la position de reprise et la transition entre deux occurrences du même enregistrement en répétition. Les tests vérifient aussi le passage de l’identifiant dans le Bundle Media3, la perte d’une source et le changement de compte. |
| Rejeu ciblé de navigation Media3 | 17 réussites, dont la mise à jour du dossier après confirmation cloud, l’absence de notification sur un simple changement de progression et la pagination des grandes listes. |
| Bilan cloud Android | 10 tests ciblés réussis avec la lecture et la navigation. Le suivi reste actif pendant un traitement ou une reprise transitoire et s’arrête quand le statut est définitif ou requiert un choix. |
| Demande cloud Android | Quatre tests Room/Ktor vérifient l’ordre des appels, l’absence de demande si la synchronisation échoue, la consultation du bilan et le refus d’une requête après déconnexion. Une édition d’une autre playlist ne force aucune synchronisation. |
| Lecteurs Spotify/Deezer | 8 tests sur fixtures, dont une page sautée ou répétée avec total inchangé, une entrée Spotify malformée et une lecture Deezer complète sur deux pages. |
| Rejeu d’une actualisation Android | 11 tests ciblés avec migration Room et lecteurs fournisseur. Un accusé retardé acquitte la demande correspondante et préserve le bilan plus récent provenant d’un autre appareil. |
| Lint Android | 0 erreur, 51 avertissements restant au rapport. Deux diagnostics d’indentation corrigés. |
| Suites complètes Shared / Desktop | 12 tests Shared Android, 12 Shared Desktop et 30 Desktop réussis |
| Suite Android complète | 173 sur 173 après le correctif de demande cloud. L’indexation des 50 000 titres passe le seuil de 3 s. Elle prenait 2,48 s au commit `10b35096` et 5,31 s sur le commit de départ. |
| PostgreSQL et HTTP | Huit migrations rejouées, cinq scénarios playlist, tests d’isolation et d’annulation réussis. Inventaire synthétique, reprise du même job dans deux processus, bilan isolé par compte et flux HTTP partiel vérifiés. |
| Supabase Auth et PostgREST locaux | Deux comptes et deux sessions distinctes du propriétaire. La seconde session retrouve la référence Deezer, le bilan, le morceau ajouté et ses métadonnées avec l’occurrence manuelle intacte. Rejeu stable de l’actualisation et de l’action cloud. Un inventaire invalide laisse le snapshot intact. Accès anonyme et accès croisé refusés. |
| Démarrage et reprise du worker via PostgREST | Le démarrage FastAPI reprend le même job après interruption et sert les octets du fichier synthétique. L’entrée sans source autorisée reçoit un statut explicite. Le second compte peut lire via le cache natif authentifié, avec sa bibliothèque personnelle intacte. |

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
  autorisées. Vérifier les droits de récupération, de stockage et de
  lecture via le cache natif partagé entre comptes authentifiés. Les
  tests utilisent des fichiers synthétiques.
- Vérification sur l’environnement de préproduction avec sa configuration
  Supabase, ses versions de composants et son mode de signature des jetons.
  Le rejeu local Supabase couvre une configuration HS256 avec certificats
  éphémères et comptes synthétiques.

Aucune révision de production, OTA ou base réelle n’entre dans la
validation locale. Chaque intervention distante doit faire l’objet d’une
validation distincte.
