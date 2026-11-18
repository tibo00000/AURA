# Jobs Asynchrones

## Types de jobs
- telechargement audio futur
- enrichissement de metadonnees
- generation d'artefacts de pochettes ou caches
- maintenance technique

## Cycle de vie
- `queued`
- `running`
- `succeeded`
- `failed`
- `cancelled`

## Regles
- Chaque job porte un identifiant stable.
- Les progressions doivent etre lisibles depuis Android et via MCP.
- Un echec doit exposer un code et un message diagnostic.

## Autorisation et annulation des téléchargements (SEC-003, finding 4)

- Le propriétaire provient exclusivement du JWT vérifié. Une annulation adverse et un
  identifiant absent donnent le même refus 404, sans mutation mémoire, SQL ou disque.
- Avant chaque mutation du cycle de vie, le backend relit le job sous sa garde et
  conditionne l'écriture SQL par ID, propriétaire, état et compteur attendus. Une
  erreur SQL ne constitue jamais une preuve de suppression.
- Chaque exécution possède une identité interne, un signal d'arrêt thread-safe et
  un répertoire de travail propre. Le compteur public `attempt_count` ne remplace
  pas cette identité. Création, réparation, retry et résolution utilisent la même
  admission ; une tentative active ne peut pas être remplacée par une autre.
- Publication et annulation sont sérialisées. Après l'annulation effective, aucun
  cache, lien personnel, état de réussite ou progression ne peut être publié par
  cette tentative. Une publication achevée avant l'annulation reste acquise.
- DELETE conserve le contrat 204 vide après suppression logique SQL vérifiée et
  interdiction de publication. Le thread natif peut terminer son arrêt et nettoyer
  son répertoire privé en arrière-plan. Un 204 ne garantit pas l'arrêt instantané
  de yt-dlp ou FFmpeg. Une impossibilité de garantir la suppression est une erreur.
- Le contexte reste suivi jusqu'à l'achèvement réel du travail natif. Un ancien
  `finally` ne retire jamais le contexte d'une tentative plus récente.
- Les nouveaux IDs restent `job_<ULID>`. Les IDs historiques sont des chaînes
  opaques sûres, bornées à 128 caractères ASCII alphanumériques, `_` et `-`,
  commençant par `job_`. Aucun décodage supplémentaire ni normalisation n'est admis.

## Supervision et admission (SEC-003.2)

Les opérations create/retry/resolve/reassign sont supervisées par le service :
une annulation de l'appelant, même répétée, ne détache pas une mutation SQL déjà
engagée. La finalisation possède sa propre tâche suivie et attend la fin native
avant tout retrait de contexte. Le shutdown ferme l'admission puis attend ces tâches.

Ordre unique des gardes : admission propriétaire/piste, verrou d'exécution piste,
puis garde job. Les chemins utilisent les sous-séquences nécessaires : commandes
sans publication directe admission/job, création depuis cache admission/piste/job,
workers piste/job, DELETE et GET job seulement. Une garde job n'attend jamais un
verrou piste ou une admission. Les lignes SQL sont relues après admission.
Une réassignation peut clôturer l'ancien job, mais garde sa tentative jusqu'à fin
native ; la nouvelle admission attend sa finalisation avant de devenir active.

Les recherches de cache et GET fichier sont sans backfill ni publication. GET
relit le propriétaire sous garde job ; DELETE et la sélection de fichier sont
ordonnés, sans promettre l'interruption d'un flux déjà autorisé. Une forme SQL
invalide donne 503 ; un CAS zéro ligne valide reste un conflit/une absence.

Une réponse de mutation mal formée reste un 503, même si une relecture confirme
son commit. Dans ce cas le service supervise la ligne queued confirmée. Une perte
de transport peut être réconciliée par relecture de l'identité attendue. Si son
issue demeure inconnue, une intention suivie bloque les quatre chemins d'admission
de la paire. La reprise rejoue exclusivement le même ID d'insertion ou le même CAS
de restart ; elle conserve la source choisie et n'incrémente pas deux fois le
compteur. Une absence confirmée après une insertion incertaine permet de retenter
ce même ID, jamais d'en générer un second. Une suppression vérifiée clôt l'intention.

## Reprises des remplacements partagés (SEC-005.1)

`download_jobs.operation_type` persisté est l'autorité pour distinguer une
réassignation globale d'un téléchargement ordinaire. Un `trigger` transitoire
ne peut ni accorder ni retirer la propagation partagée ou son historique SQLite.
Une tentative manuelle contourne toujours l'ancien cache. Sa publication et son
journal restent soumis aux gardes de propriétaire, de tentative et d'état SQL.

Le schéma actuel ne persiste pas la vidéo choisie. Un retry manuel est donc
refusé avec HTTP400 avant toute mutation ; une nouvelle réassignation explicite
ou une résolution avec un `video_id` non vide est nécessaire. La réparation
implicite par POST /downloads d'un ancien job manuel sans fichier est également
refusée. Un worker manuel ayant perdu sa source termine en échec avec
`replacement_source_required`, sans recherche automatique ni réutilisation du
cache, et avec une entrée d'historique échouée. Recovery reste désactivé.
Une résolution explicite conserve `operation_type=manual_reassign` et journalise
aussi les échecs fournisseur et les effets partiels. Le motif, optionnel et
transitoire, peut être absent après redémarrage ; aucune source n'est déduite du
cache, des candidats ou d'un ancien journal. Les téléchargements ordinaires
gardent leurs règles de retry, résolution et réutilisation du cache.

Une admission manuelle encore incertaine bloque une nouvelle résolution avec
HTTP409 ; le nouveau choix ne rejoue jamais silencieusement l'ancienne source.
La réconciliation supervisée de l'intention initiale et DELETE conservent les
garanties SEC-003.2, sans mutation de récupération globale.

## Limites opérationnelles

### Intentions d’acquisition d’une playlist

Une intention est créée dans la transaction de la mutation playlist qui
l’a demandée. L’intention identifie le compte, la piste et la provenance de
la demande. L’admission audio est ensuite traitée par le serveur, avec un
identifiant de job réservé de façon durable. Les ajouts répétés d’une
même piste réutilisent l’intention du compte et de cette piste.

Le stockage des intentions est réservé au backend. Les requêtes utilisateur
passent par des RPC qui vérifient le compte issu du JWT et l’appartenance de
la playlist. Le job conserve son propre cycle de vie et sa progression
dans `download_jobs`. La reprise recourt aux intentions arrivées à échéance,
avec bail et comparaison du jeton avant toute finalisation. L’activation
de l’acquisition réelle requiert une source vérifiée côté serveur.

Le worker contrôle les intentions dès le démarrage de FastAPI puis à
intervalles réguliers. PostgreSQL attribue les demandes arrivées à échéance
via `FOR UPDATE SKIP LOCKED`, y compris celles dont le bail a expiré pendant
un arrêt du VPS. Un jeton protège la réservation d’ID de job et les
changements d’état. Une réservation survit à un crash avant ou après
l’insertion du job. Le worker retrouve un job par son identité persistée,
utilise l’admission protégée existante et relance uniquement les tentatives
orphelines rattachées à une intention. Les cas de choix de version et la
progression restent gérés par `download_jobs`.

Les jobs créés par le worker portent le marqueur persistant
`playlist_acquisition`. Leur réparation et leur relance passent par
l’intention et la source autorisée par le manifeste. Les routes
historiques gardent leur comportement pour les jobs ordinaires,
y compris ceux simplement rattachés à une intention.
Un arrêt supervisé laisse le job de playlist en attente avec le même
identifiant. Après un arrêt brutal, le worker reprend le job orphelin à
l’expiration de son bail. La relance vérifie à nouveau le manifeste.
Quand l’intention conserve le titre et l’artiste, le téléchargement
utilise ces métadonnées persistées et la source audio autorisée même
pendant une panne du catalogue Deezer.
Si PostgREST est temporairement indisponible pendant le passage à l’état
`running`, le job de playlist reste récupérable avec le même identifiant.
Le poller reprend l’intention à sa prochaine échéance et revérifie le
manifeste avant de démarrer une nouvelle tentative. Cette reprise couvre
la transition qui précède la lecture ou la publication d’un fichier.
Le déploiement actuel du downloader utilise un processus d’exécution.
Les baux empêchent deux pollers de réserver ou redémarrer le même job
avec le même compteur. Le lancement d’un second processus de
téléchargement nécessite d’abord des verrous de publication interprocessus.

`ENABLE_PLAYLIST_ACQUISITION_WORKER` active le poll serveur. En mode `manifest`, pour autoriser
un transfert, le manifeste local au backend désigne l’enregistrement, la
vidéo exacte, l’autorisation cloud et sa provenance. Sans entrée validée,
le worker peut confirmer un fichier déjà lisible ou retrouver un job actif
et affiche une source non autorisée pour les autres titres. Il ne lance
aucune recherche heuristique pour ces intentions.

Pour les essais sans liste de titres, `PLAYLIST_AUDIO_SOURCE_MODE=automatic`
autorise le worker à utiliser le résolveur YouTube Music existant avant
l'admission du job. La valeur par défaut reste `manifest`. Une entrée explicite
du manifeste garde la priorité. Le worker transmet toujours une vidéo précise
au service de téléchargement : ses gardes de bail, d'identité, de compte et
d'exclusion restent identiques. Ce mode exprime un choix d'exécution du serveur,
pas une vérification des droits sur les sources audio.

Une recherche ambiguë ne sélectionne pas arbitrairement le premier résultat :
l'intention indique `audio_source_ambiguous`, sans démarrer de transfert. Une
absence de résultat donne `audio_source_unresolved`. Une erreur technique de
résolution conserve la reprise avec temporisation. Les intentions déjà refusées
peuvent être relancées par l'action cloud de la playlist, sans recréer ses entrées.

Une occurrence locale rattachée explicitement à Spotify ou Deezer utilise
l’identifiant Aura du catalogue pour demander l’acquisition. Le même job
peut ainsi servir l’occurrence locale et les occurrences du catalogue.
La copie cloud existante du compte est prioritaire. Des rattachements
contradictoires pour le même titre local dans une playlist demandent une
correction humaine avant une acquisition automatique.

La coordination concerne un seul processus Uvicorn et une seule instance de service
responsable des mutations. Une exécution multiworker nécessite une coordination
interprocessus avant activation. Le passage à une image corrigée doit terminer les
workers de l'image précédente ; un thread exécutant ce code précédent ne bénéficie
pas des nouvelles gardes. Recovery et backfill restent désactivés.
Le nettoyage des orphelins de cache est également neutralisé, même en cas d'appel
explicite, jusqu'à preuve d'une coordination commune avec les publications.

Une panne SQL persistante peut empêcher la finalisation du statut. Le backend ne
publie alors rien et ne présente pas une suppression incertaine comme réussie.
Les intentions mémoire ne sont pas un journal durable : une destruction du processus
pendant une panne SQL peut laisser un job sans worker. Ne pas réactiver recovery
pour contourner cette limite. Après rétablissement, une suppression explicite par
le propriétaire permet une nouvelle création ; aucune réparation globale ni purge
automatique de données utilisateur n'est déclenchée. L'arrêt gracieux attend le
travail natif réel et peut prolonger la maintenance si ce travail ne répond pas.

## Vérification locale

Depuis une copie sans `.env` ni credentials, avec configuration synthétique et
réseau extérieur bloqué :

```text
python -B -m pytest -p no:cacheprovider tests/test_sec0032_regressions.py tests/test_download_admission_recovery.py tests/test_download_cache_provenance.py tests/test_download_job_security.py tests/test_download_delete_contract.py tests/test_download_service_di.py tests/test_reassign_audio.py tests/test_global_cache.py tests/test_smart_song_search_multi_artist.py
python -B server/scripts/test_user_isolation_postgres.py --image postgres:17-alpine --download-cancellation
```

La seconde commande s'exécute depuis la racine de cette copie et crée uniquement
un conteneur PostgreSQL Docker local jetable, sans réseau ni port publié. Elle
contrôle les préconditions/RLS du lot 2 et les predicates CAS/rollback du lot 3.
Les tests ASGI utilisent des JWT signés synthétiques et le test SDK un transport
HTTP local simulé. Ils ne constituent pas une intégration Supabase réelle.

## Integration produit
- L'ecran `Downloads` consomme directement cet etat.
- Les logs applicatifs lient chaque job a ses erreurs et a son provider source.
