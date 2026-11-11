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
