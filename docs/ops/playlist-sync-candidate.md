# Candidat de synchronisation des playlists Aura

Branche `fix/playlist-sync-program`, issue du commit `03133655`.
Le chantier de démarrage Android et les protections de session intégrés
à ce commit font partie du point de comparaison. La version client reste
0.4.3, code 7, pendant la préparation du candidat.

## Déploiement backend autorisé du 17 novembre 2026

Le suivi Android du 18 novembre ci-dessous ne modifie pas ce déploiement.

L’utilisateur a autorisé les neuf migrations nommées ci-dessous et le
déploiement du backend avec les acquisitions automatiques désactivées.
Le backend actif provient du commit
`2e2b9559bd58330e39f7ca813486e3d37c1e20d1`, image Docker
`sha256:8fcab820e8b0c0da25a2c1f7087b51899ef23bb2ae679298c86618dc503563f0`.
La vérification des 59 fichiers applicatifs de l’image correspond au commit.
La branche de travail reste distincte de `main`.

Les neuf migrations ont été appliquées à `neosgkuccazvgkzwzjzh` puis
contrôlées dans l’historique distant. Le prérequis `operation_type` des jobs
était déjà présent. Le conteneur `aura-api` est sain, `/health` répond 200,
les quatre nouvelles routes sont présentes et les consultations anonymes
du snapshot et du bilan sont refusées avec 401. Le snapshot exécuté sous
le rôle authentifié restitue 4 playlists et 251 occurrences au propriétaire,
et aucune au second compte sans playlist. Toutes les tables playlist ont
RLS activé. Les RPC de worker sont inaccessibles aux rôles clients.

Les valeurs effectives sont `ENABLE_PLAYLIST_ACQUISITION_WORKER=false`,
`ENABLE_STARTUP_RECOVERY=false`, `ENABLE_CACHE_BACKFILL=false` et
`PLAYLIST_AUTHORIZED_AUDIO_MANIFEST` vide. Une actualisation peut ajouter
les titres et persister une intention audio, mais le worker ne télécharge
pas les fichiers manquants. Les routes de téléchargement manuel historiques
conservent leur fonctionnement. Après redémarrage, les neuf tables exportées
sont identiques sur tous leurs champs préexistants, y compris les 449 jobs.
Les 5 038 fichiers sauvegardés sont présents avec leur taille initiale.
Aucun traceback ni ligne ERROR n’a été relevé dans les logs de démarrage.

La sauvegarde est exclusivement sur le VPS, dans le répertoire privé
`/root/aura-playlist-sync-20261117-2e2b9559` (0700). Elle comprend
`database-before.json`, `schema-before.json`, `audio-before.tar` et la
configuration antérieure privée. L’export des données est vérifié par
SHA-256 `2426265dba7d13f7c4a35ca2f764668aa5cb8b664072cbb00427817a47bb62bf`.
L’archive audio fait 7 562 864 640 octets et son SHA-256 est
`5e16a75896084a587c53f891fb53013376c9c6a4c2d7a378d35e64f2560bee53`.
Elle conserve les liens physiques. Les données et l’archive audio n’ont
pas été rapatriées. La copie locale temporaire du catalogue de schéma a
été transférée puis supprimée après contrôle. Conserver la sauvegarde
jusqu’à la validation explicite du fonctionnement en production par
l’utilisateur, puis supprimer uniquement les fichiers de sauvegarde.
Ne pas supprimer le répertoire entier, qui contient aussi l’override actif
`compose.playlists.json` et les preuves d’intervention.

Le retour arrière backend utilise l’image antérieure conservée
`sha256:cd2a68b333fcafd5753a3654d34be12a5dc5a492d11f0469ffb9446d7d824e91`
et la liste Compose de `plan.json` sans son dernier override playlist.
Conserver les schémas additifs et les données, ainsi que les trois flags
d’exécution désactivés. L’ancien backend perd les protections applicatives
décrites dans la section Retour arrière, même si les triggers persistent.

L’advisor Supabase ne signale pas d’erreur. Les nouvelles observations
concernent les trois RPC SECURITY DEFINER volontairement exposées au rôle
authentifié, les deux tables consultables avec RLS et la table d’exclusions
dont RLS interdit tout accès client direct. Les fonctions exposées contrôlent
`auth.uid()`, la propriété de la playlist et leur `search_path` est vide.
Les avertissements de catalogue et de protection des mots de passe existaient
avant l’intervention. Références
[RPC SECURITY DEFINER](https://supabase.com/docs/guides/database/database-linter?lint=0029_authenticated_security_definer_function_executable),
[visibilité GraphQL](https://supabase.com/docs/guides/database/database-linter?lint=0027_pg_graphql_authenticated_table_exposed)
et [RLS sans politique](https://supabase.com/docs/guides/database/database-linter?lint=0008_rls_enabled_no_policy).

L’OTA publiée reste 0.4.3/code 7, avec l’empreinte APK
`7967b5f5ecb070755af442fd8bcf255e0108208e26d7cd0d131226410c0f719f`.
L’APK candidat local conserve le certificat et l’empreinte consignés plus bas.
L’installation sur téléphone et les parcours réels restent à effectuer.

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
| Suivi D/F | `64a1b7cc`, `a130805d`, `aad6ad6b` | Reprise des admissions, lecture des fichiers catalogue historiques et audio des occurrences locales rattachées |
| Suivi C/F | `2b6cf2b5` | Exclusions confirmées conservées lors d’une suppression manuelle |
| Suivi E/F | `c9e8121b`, `51686b9f` | Éditions acquittées avant la demande cloud et contrôle local groupé des URI MediaStore |
| Suivi E/F | `614456e9` | Consultation et reprise de la lecture du bilan cloud sans demande de téléchargement |
| Suivi E/F | `61e654ac` | Source audio de la file préparée conservée jusqu’au service Media3 |
| Suivi E/F | `d702b95c` | Sélection d’une occurrence explicite dans Media3 et conservation de la lecture en cas de refus |

Historique complet depuis le point de départ avec
`git log --oneline 03133655..fix/playlist-sync-program`.

## Essai direct sur téléphone

### Retours Android du 18 novembre

Le premier APK a été installé manuellement. Le test sur quatre titres a
confirmé la persistance des choix de correspondance et l’ajout d’une nouveauté,
mais a montré une validation trop répétitive, une source perdue lors de
l’import et un nuage affiché pour un titre sans fichier cloud confirmé.
La copie locale de NUEVAYoL a été observée dans MediaStore. Cette observation
ne prouve pas à elle seule l’identité catalogue de l’entrée Spotify et du
fichier déjà indexé : les anciens enregistrements ne sont pas fusionnés.

Le correctif est limité à Android, avec les contrats backend et Room
existants. L’import Spotify/Deezer utilise le lecteur fournisseur complet,
conserve les identifiants et associe sa source dans Aura. Il réutilise
l’actualisation et ses intentions persistantes, puis récupère le snapshot
avant d’ouvrir la playlist. Une synchronisation désactivée est signalée
avant création ; une erreur ultérieure conserve l’identité de la playlist
créée et ouvre celle-ci avec un message explicite. Les routes historiques
de téléchargement ne sont pas appelées par cet import.

Pour les anciennes références, une confirmation groupée des propositions
uniques remplace les validations répétées. Les copies dans la bibliothèque,
même hors de la playlist, peuvent être choisies explicitement. Plusieurs
occurrences ou plusieurs versions de source demandent un choix individuel.
La résolution automatique d’une copie audio locale exige un identifiant
catalogue exact, conserve les occurrences et ne réécrit aucune métadonnée.
Une entrée déjà ajoutée sous une référence différente n’est pas réaffectée
automatiquement ; son éventuelle réconciliation reste un choix utilisateur.

Le nuage signifie un fichier confirmé. Les demandes en attente utilisent
l’indicateur de file existant ; seul un job réellement actif est présenté
comme un transfert. Un fichier local passe avant une demande cloud en
attente. Le résumé permanent des ajouts est retiré, le compteur cloud
disparaît quand tout est disponible et le détail reste accessible au menu.
Les propositions sont calculées sur `Dispatchers.Default`, à l’ouverture
de la fiche ou à l’actualisation, sans nouveau traitement au démarrage.
Les créations et ajouts issus d’un import ou d’une confirmation vérifient
la session dans leur transaction Room + outbox ; un changement de compte
annule les deux écritures. La synchronisation garde le même scope.

Reprise du stash `5688e03f` dans un worktree sur
`fix/playlist-sync-program`, sans modifier les travaux Desktop présents
dans le worktree principal. Le stash est conservé. Le fichier préexistant
`ATABLE_VPS_PREFLIGHT.md` est exclu du correctif.

La suite Android complète a réussi avec 197 tests, sans échec ni test ignoré.
Le lint passe avec 0 erreur, 69 avertissements et 3 informations ; les
avertissements restent consignés dans le rapport et ne sont pas présentés
comme résolus. Commandes ciblées `:androidApp:testDebugUnitTest` et
`:androidApp:lintDebug` via le wrapper, à un worker.
Elle couvre six scénarios d’import et d’interruption, les annulations
transactionnelles, les alias catalogue et les états d’affichage. Les tests
Room utilisent Java 21 x64 ; Java ARM64 provoquait une erreur du runtime
natif Robolectric sur Windows avant exécution des scénarios SQLite.
L’essai visuel du nouveau client sur téléphone reste à réaliser.
Le suivi ne change ni la version 0.4.3/code 7, ni l’OTA, ni les acquisitions
automatiques désactivées. Aucune migration ni mise à jour backend requise.
L’assemblage Android a ensuite été demandé explicitement par l’utilisateur.
`:androidApp:assembleDebug` a réussi depuis le commit
`5d5e61e9414426c5f868ed217e31c859b076eda8` :
`aura-playlist-sync-20261118.apk`, 92 596 281 octets, package `com.aura.music`,
version 0.4.3/code 7. SHA-256
`ca2f1b2ec76abb0f9bd213ee7f7649d750e4f32d95a779d07e4fa69da4b4b73f`.
La signature v2 est valide et le certificat est identique à celui du
candidat installé le 17 novembre, SHA-256
`9bdf88e7b7b10d8e3ce5e1b9232b6e8f9554e4f85f1113accf981b1705338307`.
La copie de livraison possède la même empreinte que la sortie Gradle.
Les marqueurs des nouveaux helpers sont présents dans le DEX, les classes
de test sont absentes et la cible backend est celle du candidat précédent.
Le manifeste `aura-playlist-sync-20261118.verification.json` accompagne l’APK.
L’installation par-dessus avec `adb install -r` et l’essai sont laissés
à l’utilisateur, conformément à sa demande. Aucune installation ni OTA
n’a été exécutée par l’agent.

Le parcours retenu pour l’usage personnel commence par une playlist dédiée.
Sa suppression et sa recréation sont acceptées si un problème reste limité à
cette playlist. Une nouvelle association à la source sera alors nécessaire,
et les exclusions et bilans de la playlist supprimée repartiront à zéro.

1. Préparer le backend et les migrations nommées ci-dessous après validation
   distincte de leur application. Cette étape et le déploiement backend
   sont réalisés le 17 novembre, avec les contrôles consignés ci-dessus.
2. Préparer l’APK standard à la version courante, vérifier son certificat
   contre la version installée et son empreinte SHA-256. Une installation
   par-dessus garde la base locale et exécute les migrations Room.
3. Choisir une seule playlist d’essai et relever son contenu. Vérifier
   l’association, deux actualisations successives, le retrait d’un titre et
   son absence au prochain rafraîchissement, puis la lecture.
4. Comparer une autre playlist contenant le même titre et lire son audio.
   Si une recréation devient utile, désigner explicitement la playlist avant
   sa suppression. Étendre ensuite les essais aux playlists usuelles.

Le test `playlist_recreation_scope.sql` vérifie les suppressions batch et REST,
les autres comptes et occurrences, ainsi que la conservation du job partagé.
Le contrôle HTTP local compare aussi les empreintes des octets audio et des
métadonnées avant et après recréation. L’environnement de test durable sera
repris après ce parcours sur téléphone.

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
   `20261114184616_playlist_playback_occurrence.sql`,
   `20261116223507_playlist_confirmed_exclusions.sql` puis
   `20261117115916_playlist_linked_audio.sql`.
   Vérifier aussi la présence de la migration existante
   `005_add_download_job_operation_type.sql`.
3. Déployer le backend seul et vérifier les contrats historiques, le nouvel
   inventaire et les RPC en lecture avec un compte de validation. Vérifier dans
   la configuration effective du processus que `ENABLE_PLAYLIST_ACQUISITION_WORKER`,
   `ENABLE_STARTUP_RECOVERY` et `ENABLE_CACHE_BACKFILL` valent `false` et que
   `PLAYLIST_AUTHORIZED_AUDIO_MANIFEST` est vide. Relever l’état des jobs et du
   cache avant et après le démarrage pour confirmer qu’ils n’ont pas été repris
   ou alimentés par les traitements historiques.
4. Préparer les clients Android et Desktop, vérifier les migrations Room
   successives et l’aller-retour d’une playlist historique. L’activation
   fournisseur dépend des droits de lecture réels du compte et de
   l’application Aura. Relever le code de version minimal uniquement si
   des clients historiques doivent être retirés lors d’une release.
5. Activer le worker sur une seule instance d’exécution après la preuve de
   reprise et la revue des paramètres. Garder `ENABLE_STARTUP_RECOVERY=false`
   et `ENABLE_CACHE_BACKFILL=false` pendant l’activation. En préproduction,
   la preuve de reprise peut utiliser un manifeste de test et un fichier
   autorisé. En production, renseigner `PLAYLIST_AUTHORIZED_AUDIO_MANIFEST`
   uniquement après validation de la provenance et des droits de
   téléchargement, de stockage et de lecture des sources concernées.

## Contrats à vérifier

| Contrat | Changement et compatibilité |
| --- | --- |
| `GET /me/playlists/snapshot` | Inventaire cohérent, révisé et propre au compte, utilisé par Android et Desktop. L’inventaire historique garde son format. |
| `POST /me/sync/push-batch` | Les clients migrés envoient `playlist_sync_version=2` et lisent la révision acquittée par mutation. Les clients historiques conservent les mutations historiques. |
| `PUT /me/playlists/{id}/source` | Référence facultative et contrôle de révision. Le retrait utilise une référence nulle. |
| `POST /me/playlists/{id}/refresh` | Inventaire complet fourni par Android, révision de source attendue et identifiant stable au rejeu. Le bilan confirmé est disponible dans le snapshot. |
| `GET/POST /me/playlists/{id}/acquisitions` | Bilan groupé contrôlé par compte. `audio_track_id` est facultatif et indique la source audio d’un rattachement confirmé. `track_id` garde l’identité dans la playlist. POST admet des intentions, avec reprise ciblée facultative. |
| `GET/PUT /me/playback-snapshot` et sync batch | Champ nullable `playlist_occurrence_id`. La restauration historique utilise le titre lorsqu’il identifie une occurrence unique. |
| `GET /me/sync/files` | Forme inchangée. Une entrée de métadonnées exige désormais un fichier audio présent et non vide. |
| Routes historiques de téléchargement | Les jobs nés d’une intention sont relancés depuis la playlist avec le manifeste. Les jobs indépendants gardent leur comportement antérieur. |

Les migrations Room 5 → 9 préservent les playlists existantes, les
mutations locales en attente et les liens audio locaux. Le code de
version minimal peut rester au seuil actuel si les vérifications de
compatibilité sur la cible confirment les contrats historiques.
Pour le parcours des occurrences locales rattachées, vérifier la version
Android qui lit `audio_track_id` avec le backend candidat avant activation.

## Retour arrière

En cas d’incident d’acquisition, couper d’abord le worker avec
`ENABLE_PLAYLIST_ACQUISITION_WORKER=false` et garder
`ENABLE_STARTUP_RECOVERY=false` et `ENABLE_CACHE_BACKFILL=false`.
Laisser expirer les baux et conserver
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

Si le rattachement audio doit être désactivé, suspendre le worker puis
restaurer ensemble le backend et la définition précédente du RPC de
bilan. Les intentions, jobs et fichiers déjà enregistrés restent utilisables
sous leur identifiant catalogue. Les occurrences locales restent en place.

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
  Le test exige les dépendances Python du backend. Il rejoue les neuf
  migrations, les scénarios playlist, 97 assertions d’isolation,
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
| Python | 406 réussites. Une panne temporaire de PostgREST avant le début du téléchargement laisse le job de playlist récupérable. Les tests couvrent la lecture croisée des identifiants fournisseur exacts, l’isolation privée, la lecture par plage et la priorité d’une copie privée déjà disponible. |
| Correction des échecs CI | 406 tests backend réussis sur Python 3.12 Linux après chargement des dépendances audio réelles depuis `conftest.py`, avant les substitutions conditionnelles des anciens modules de test. Aucun changement du runtime applicatif. Les 20 tests Android Media3 et contrôleurs passent dans un checkout avec l’origine HTTPS fictive désormais fournie par la CI. |
| Recréation d’une playlist | Nouveau scénario PostgreSQL réussi. Suppressions batch et REST limitées à la playlist, occurrences et exclusions des autres playlists conservées, job et intention partagés inchangés. Le contrôle HTTP local retrouve aussi les mêmes empreintes audio/métadonnées et une réponse de lecture partielle 206. |
| APK pour l’essai direct | `:androidApp:assembleDebug` réussi. Package `com.aura.music`, version 0.4.3/code 7. Signature v2 vérifiée, certificat SHA-256 `9bdf88e7b7b10d8e3ce5e1b9232b6e8f9554e4f85f1113accf981b1705338307` identique à l’APK publié. Empreinte du candidat `2c3c74fcb0a785c2c35de11ef99c92e65c33e7bd1dc81d1ceb133793be3b1859`. Copie de la version publiée téléchargée et vérifiée. Installation en attente du backend compatible et d’un téléphone connecté. |
| Tests Android ciblés | 18 réussites, dont reprise Room et changement de compte |
| Rejeu ciblé Media3, file et contrôleurs | 21 réussites. ExoPlayer vérifie la position de reprise et la transition entre deux occurrences du même enregistrement en répétition. Les tests vérifient aussi le passage de l’identifiant dans le Bundle Media3, la perte d’une source et le changement de compte. |
| Rejeu ciblé de navigation Media3 | 17 réussites, dont la mise à jour du dossier après confirmation cloud, l’absence de notification sur un simple changement de progression et la pagination des grandes listes. |
| Entrées MediaStore disparues | Deux tests avec MediaStore simulé couvrent le filtrage à la restauration, le recours au cloud confirmé, la conservation des occurrences et le regroupement de 501 identifiants en deux requêtes. Un lot en erreur conserve son dernier état connu. |
| Bilan cloud Android | 11 tests ciblés réussis sur les demandes, les statuts et la file de lecture. Une réponse HTTP 503 préserve le dernier bilan et la relance de la consultation utilise exclusivement GET. Un bilan vide est un résultat confirmé. Une première erreur reste distincte d’un bilan confirmé et son état est effacé à la déconnexion. Le suivi continue pendant une acquisition active et s’arrête pour les états définitifs ou qui requièrent un choix. |
| Demande cloud Android | Quatre tests Room/Ktor vérifient l’ordre des appels, l’absence de demande si la synchronisation échoue, la consultation du bilan et le refus d’une requête après déconnexion. Une édition d’une autre playlist ne force aucune synchronisation. |
| Lecteurs Spotify/Deezer | 8 tests sur fixtures, dont une page sautée ou répétée avec total inchangé, une entrée Spotify malformée et une lecture Deezer complète sur deux pages. |
| Rejeu d’une actualisation Android | 11 tests ciblés avec migration Room et lecteurs fournisseur. Un accusé retardé acquitte la demande correspondante et préserve le bilan plus récent provenant d’un autre appareil. |
| Lint Android | 0 erreur, 51 avertissements au commit `d702b95c`, soit les mêmes totaux qu’au dernier relevé `51686b9f`. |
| Suites complètes Shared / Desktop | 12 tests Shared Android, 12 Shared Desktop et 30 Desktop réussis |
| Suite Android complète | 181 sur 181 après la correction des sélections Media3. Sur la même révision, l’indexation des 50 000 titres a pris 3,25 s après compilation puis 1,08 s au rejeu à un worker, pour un seuil de 3 s. Elle prenait 2,48 s au commit `10b35096` et 5,31 s sur le commit de départ. Le démarrage Android sur appareil reste à mesurer. |
| PostgreSQL et HTTP | Neuf migrations rejouées, six scénarios playlist, tests d’isolation et d’annulation réussis. Inventaire synthétique, reprise du même job dans deux processus, bilan isolé par compte et flux HTTP partiel vérifiés. |
| Supabase Auth et PostgREST locaux | Deux comptes et deux sessions distinctes du propriétaire. La seconde session retrouve la référence Deezer, le bilan, le morceau ajouté et ses métadonnées avec l’occurrence manuelle intacte. Rejeu stable de l’actualisation et de l’action cloud. Un inventaire invalide laisse le snapshot intact. Accès anonyme et accès croisé refusés. |
| Démarrage et reprise du worker via PostgREST | Le démarrage FastAPI reprend le même job après interruption et sert les octets du fichier synthétique. L’entrée sans source autorisée reçoit un statut explicite. Le second compte peut lire via le cache natif authentifié, avec sa bibliothèque personnelle intacte. |
| Occurrence locale rattachée | PostgreSQL et Supabase Auth/PostgREST confirment qu’une occurrence locale et une occurrence catalogue de deux playlists partagent le même job jusqu’après redémarrage. Le bilan préserve les identifiants côté playlist. Media3 conserve l’occurrence et sa position de reprise en utilisant l’identifiant audio confirmé pour le flux. |
| Admission Media3 depuis l’application | 21 tests ciblés réussis. Le service garde la copie locale résolue ou le fichier cloud confirmé pour chaque occurrence de la file. Un contrôleur externe ne peut pas utiliser les métadonnées d’occurrence fournies par le client pour choisir la source. |
| Sélection depuis le navigateur Media3 | 23 tests ciblés réussis. Une demande ordinaire conserve le parcours bibliothèque après la navigation dans une playlist. Un identifiant de dossier sélectionne l’occurrence exacte, même en présence de doublons. Une occurrence indisponible, un identifiant mal formé ou une demande issue de la session quittée laisse la file active en place. |

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
  Retirer un fichier de test de MediaStore et rouvrir la playlist.
  Vérifier que son occurrence reste visible et que le lecteur utilise
  la source cloud si elle est déjà confirmée, puis refaire le parcours
  avec le même fichier sans copie cloud.
  Ouvrir dans Android Auto une playlist composée uniquement de titres
  cloud lors d’un démarrage sans cache, puis vérifier que le dossier
  affiche les titres quand le serveur confirme leur disponibilité.
  Après l’ouverture de ce dossier, demander un titre hors de la playlist
  depuis la recherche ou une commande vocale, puis sélectionner un doublon
  depuis le dossier de la playlist.
  Ouvrir le bilan depuis le menu d’une playlist synchronisée. Simuler
  une indisponibilité du serveur, vérifier l’affichage du dernier bilan
  connu et relancer sa lecture sans créer de demande de téléchargement.
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
