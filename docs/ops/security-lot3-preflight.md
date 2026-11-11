# SEC-003.4 — SEC-003.3 déployé et production rouverte

## État et périmètre

Revue SEC-003.3 favorable. Candidat `sha256:cc5fcd84e4c5c58ff095b9f6f290e34f95eddb0d26b5fa36f1998eee73fb60ec` ;
identité détaillée dans [le dossier candidat](security-lot3-candidate.md). **Déployé et vérifié le21 octobre2026**.
P1 SSH et P2 Supabase ont été autorisés par la réponse utilisateur « les deux », puis
exécutés le 20 octobre 2026. La phase T seule, ensuite autorisée, a réussi le
21 octobre : transfert privé, import vérifié et projection inactive. Résultats et
limites ci-dessous. La fenêtre ensuite autorisée a été exécutée : candidat actif et production rouverte
à16:51:44+02, après les points d’arrêt et accords détaillés ci-dessous. Aucune migration,
modification de secret ou version cliente. **GO opérationnel ; fenêtre terminée.**

## P0 — vérifié localement

Archive disponible : `scratch/sec0033/delivery/security-lot3-rc-amd64.tar.gz`, 296693027 octets,
SHA-256 `8e1f7ee55857de3f206d95f5350e32385ae0400ea0ebe7b15f207d98028c8f9e`.
Image présente dans Docker Desktop : ID ci-dessus, Linux AMD64. Aucun conteneur lancé pour P0.

```powershell
Get-FileHash -Algorithm SHA256 scratch/sec0033/delivery/security-lot3-rc-amd64.tar.gz
docker --context desktop-linux image inspect sha256:cc5fcd84e4c5c58ff095b9f6f290e34f95eddb0d26b5fa36f1998eee73fb60ec --format '{{.Id}} {{.Os}}/{{.Architecture}} {{.Size}}'
```

STOP si fichier absent, hash/ID/plateforme divergent. L'archive tar locale mesure299123200 octets.
Les 329 tests embarqués SEC-003.3 sont les preuves précédentes ; pas une nouvelle exécution.

## P1 — lecture seule VPS, autorisé et exécuté

Cible unique : `root@212.90.121.80`, `/opt/aura`, daemon Docker local Unix, projet `infra`.
Programme exact : `server/ops/sec0034/vps_readonly.py`. Transmis par stdin, jamais installé.

```powershell
$program = Get-Content -LiteralPath server/ops/sec0034/vps_readonly.py -Raw
$program | ssh -T -o BatchMode=yes -o StrictHostKeyChecking=yes -o ControlMaster=no -o ControlPath=none -o ConnectTimeout=10 root@212.90.121.80 'python3 -B - --execute-readonly'
```

| Lectures du programme | Informations retournées / critères |
| --- | --- |
| `docker inspect aura-api aura-api-dev aura-caddy`, `docker ps -q --no-trunc`, inspect des IDs en cours | API attendue lot2 `3dedda8e…619bb2`, healthy ; dev exited ; flags false, origine Supabase correcte, commandes/labels/rotation comparés. Autres conteneurs partageant stockage inscriptible/origine Supabase ou lançant une API : comptage, jamais leur environnement brut. |
| `docker compose -p infra -f … config --quiet` / `config --format json`, puis `--profile disabled-dev config` | Les cinq fichiers exacts sont listés dans le programme : deux bases VPS, containment/release lot1, `/root/aura-sec0024-staging/compose.candidate.yml` lot2. Hashes et ordre des labels de création comparés ; dev absent par défaut, Caddy ne dépend que de prod. Le profil est lu, jamais démarré. |
| Projection `-f - config --format json` avec override JSON en mémoire | Même cinq fichiers, nouvelle image et commande explicite `--workers 1`. Ce calcul n'applique aucune configuration. L'override lot1 contient `command` et masque le CMD de l'image : changer seulement l'image ne garantit donc pas le mode mono-processus. |
| `docker exec aura-caddy caddy adapt …`, GET local `wget …2019/config/`, `sha256sum …Caddyfile` | Égalité fichier hôte/bind/config active et hash de release lot1 figé ; rotation10m/3. JSON actif capturé sur VPS puis comparé, jamais exporté. Aucun validate/reload. |
| `docker exec -i aura-api python -B -`, lecture `/proc` dans API et hôte | Comptages Python/Uvicorn/gunicorn/reloader/FFmpeg/yt-dlp/Deno, autres Python hôte à qualifier ; aucun argv, environnement ou fichier audio/JSON privé retourné. Diagnostic court, pas d'import applicatif. |
| `docker volume inspect infra_aura-downloads`, `docker info --format …`, `du -x -s -B1`, statvfs | Volume audio unique, sans bind code/scripts ; sync dans ce volume ; octets audio/sync/cache/cache vérifié/workspaces/trash et fichiers partiels comptés. Aucun nom privé sorti. Les sous-totaux se recouvrent et ne s'additionnent pas : hardlinks dédupliqués par invocation du `du`. |
| `ss -ltnH`, `timedatectl show …NTPSynchronized` | Pas de socket hôte8000/8001, NTP synchronisé ; ports Docker API absents et réseau hôte refusé. Pas de lecture/modification du pare-feu. |

Les inspect/config complets restent en mémoire distante ; stdout = hashes, nombres,
booléens et IDs d'images. Aucune lecture de logs, de corps audio ou metadata.json ; aucun
secret affiché ou copié. Échec : sortie générique expurgée et STOP, sans commande de secours.
Le Docker observé et le namespace hôte ne prouvent pas l'absence de writers externes,
rootless/autre daemon ou administratifs. Un candidat mutateur inconnu impose qualification.

**Gates P1 :** hashes/commandes/montages/flags/rotation/Caddy/ports conformes ; un seul
Python/Uvicorn API, pas de gunicorn/reloader ni autre conteneur mutateur. Python hôte
inconnu, sync hors volume, chemin manquant ou visibilité insuffisante : STOP. Ne pas créer
le staging manquant ni changer un profil pour rendre le contrôle vert.
Capacité minimale proposée avant fenêtre : staging >= archive+tar+1GiB ; Docker >=
taille candidate observée+2GiB ; audio >=2GiB et10% libres, inodes non épuisés. Ce sont
des réserves opératoires conservatrices, pas un quota ni une borne du cache, dont l'éviction
est désactivée. Présenter les valeurs réelles avant de décider ; aucune purge automatique.
L'absence de publication/socket ne remplace pas les sondes externes IPv4/IPv6 avant réouverture.

## P2 — comptages Supabase, autorisé et exécuté

Projet `neosgkuccazvgkzwzjzh`, base `postgres`, connexion configurée existante uniquement.
Exécuter **exactement** `server/ops/sec0034/download_activity_readonly.sql` : transaction
READ ONLY, timeouts5s/1s, SELECT des droits puis comptages globaux queued/running/
requires_resolution/statuts inconnus, ROLLBACK. Aucun identifiant, titre, URL ou token.
Droits requis : SELECT sur download_jobs et visibilité complète déjà disponible ; aucun
rôle/grant/accès créé. Si read_only/database_matches/can_select/unfiltered_visibility
n'est pas vrai : STOP ; ne pas interpréter zéro ligne comme zéro activité. Voir [RLS Supabase](https://supabase.com/docs/guides/database/postgres/row-level-security).

## Gate d'arrêt propre — après autorisation de maintenance uniquement

Le préflight peut observer des jobs actifs : il ne les annule pas. Avant remplacement,
établir la maintenance lot1 et bloquer les nouvelles admissions, conserver deux SSH
indépendants et confirmer l'absence d'autre opérateur/writer. Attendre la fin naturelle
de queued/running et des enfants natifs ; deux observations espacées ne sont qu'un
indice. Des fichiers partiels peuvent être historiques : ne pas les supprimer.
Les threads yt-dlp ne sont pas dénombrables par `ps` ; SQL zéro/processus zéro ne suffit pas.
Ne pas appeler DELETE de l'ancien backend pour accélérer ce drain.

Après drain et autorisation explicite d'arrêt, commande future :

```bash
docker stop --signal SIGTERM --timeout -1 aura-api
docker inspect --format '{{.State.Status}} {{.State.Running}} {{.State.Pid}} {{.State.ExitCode}} {{.State.OOMKilled}}' aura-api
```

Exiger `exited false 0 0 false`, dev toujours arrêté et absence d'autre writer.
`--timeout -1` attend sans SIGKILL automatique ([documentation Docker](https://docs.docker.com/reference/cli/docker/container/stop/)).
Ne pas reprendre le délai30s du runbook lot2 pour ce gate. Si arrêt bloqué, code137/143,
OOM ou issue inconnue : maintenance prolongée et décision humaine ; ne pas lancer le
candidat ni réexposer l'ancienne image pour tenir le délai. Aucun de ces signaux/arrêts
ne fait partie de P1/P2.

## Validation après déploiement — plan, non exécuté

| Contrôle futur | Succès attendu / limites |
| --- | --- |
| Identité/confinement internes | `docker inspect --format '{{.Image}} {{.State.Running}} {{.State.Health.Status}}' aura-api` : candidat exact/running/healthy. Refaire les comparaisons avec commande nouvelle, volume conservé, pas de bind code/scripts, ports/debug/dev/recovery/backfill et logs lot1 inchangés. P1 tel quel attend lot2 : ne pas ignorer ses divergences après bascule. |
| Vérification embarquée | Original dans un conteneur isolé sans volume privé ; dans le service, contrôleur externe limité aux110 fichiers et au manifeste/script épinglés, imports/dépendances/pip. Ne pas lancer le parcours global `/app` sur le volume privé. Voir la reprise documentée ci-dessous. |
| Tests synthétiques | Si réexécution VPS autorisée : conteneur jetable du même ID, réseau none, aucun port/volume et /tmp tmpfs, variables synthétiques ; tests F1–F6/R1/R2/Auth/DELETE/Range. Les données du service ne servent jamais de fixtures. |
| JWT réel, autorisation distincte de session | Compte dédié déjà utilisé pour les tests si disponible, credentials DPAPI locaux non lus pendant P1/P2 ; login/refresh/logout, signature réelle ES256/compatibilité HS256 configurée, privé200, token invalide/expiré401. Aucun JWT en argv, console ou logs ; pas de création de compte implicite. |
| Téléchargement et DELETE réels, autorisation de fixtures distincte | Au maximum deux jobs neufs du compte dédié, piste publique/licenciée préapprouvée et IDs de test uniques : POST `/downloads`201 ; état par GET `/jobs/{job_id}` (pas GET `/downloads/{id}`), succès et GET fichier200/206 ; DELETE204 vide, puis GET job404. Annulation active vérifiée si état réellement actif observé, sinon résultat déclaré non concluant ; aucune attente bornée ne prouve seule la fin native. |
| Isolation/logs/streaming | Tentatives adverses seulement avec fixtures A/B dédiées autorisées ; jamais une bibliothèque réelle. Streaming header/query selon contrat lot1. Scan sur place des logs pour marqueurs synthétiques uniquement, reçu expurgé et trace d'erreur utile, pas de journal brut ni URL portant un JWT. Vérifier aussi journaux Caddy/app/erreur et rotation. |
| Extérieur avant réouverture | HTTPS valide, health/API/auth/Range conformes ; ports8000/8001 inaccessibles depuis une origine réellement connectée IPv4/IPv6, dev503. La réserve IPv6 reste explicite si non testable. |

Une validation réelle crée des sessions Auth et des ressources : elle n'est pas lecture
seule. Source audio et compte encore utilisable à confirmer avant ce test ; anciens
runners lot1/lot2 à allowlist d'image ne doivent pas être lancés sans adaptation revue.
Le nettoyage futur cible seulement les IDs/fichiers de fixtures créées, jamais un glob
ni le cache partagé ou un hardlink d'un autre compte. Tout gate critique en échec
interdit la réouverture ; maintenir la maintenance et demander une décision.

## Risque résiduel metadata.json et autorisations

Après le remplacement atomique audio, l'écriture JSON personnelle reste un `write_text`
en place : échec disque/arrêt peut laisser des métadonnées anciennes, tronquées ou
invalides. Audio correct mais entrée de sync ignorée/incomplète ou propagation future
refusée par empreinte. R1 ne rend pas audio+JSON transactionnels. Pas de nouvelle fuite
intercomptes démontrée ; pas de correction/reconstruction automatique dans SEC-003.4.
Préserver les fichiers en cas d'incident ; pas de backfill ni adoption privée en cache.

Autorisations **P1 audit SSH**, **P2 SELECT agrégés** et **T transfert/import/hash**
obtenues et exécutées, puis **M maintenance/drain/arrêt**, **D bascule backend avec
commande mono-processus**, **V sessions/fixtures/tests et nettoyage borné**, **R réouverture**
autorisées et exécutées pendant la fenêtre décrite plus bas. Aucun de ces accords n'en autorise un autre. Aucune migration, secret, dev/recovery/backfill.

Préparation vérifiée :10 tests locaux du collecteur/expurgation/commandes, zéro ignoré,
syntaxe du probe interne compilée. À ce stade préparatoire, aucun test SSH/SQL production
n'avait été exécuté. Deux essais
initiaux ont été bloqués à la fixture temporaire Windows, puis10/10 sont passés avec
un répertoire temporaire neuf du workspace. Pas de nouvelle validation PostgreSQL
réelle de la requête pendant la préparation ; résultats P2 maintenant constatés ci-dessous.

## Résultats P1/P2 du 20 octobre 2026

P1 observé à **2026-10-20T17:29:59.555252Z** (19:29:59 heure de Paris), sortie SSH0.
P2 effectué ensuite : deux transactions READ ONLY terminées par ROLLBACK. Le connecteur
ne retournant que le dernier SELECT, la première requête de droits a été répétée seule
avec les mêmes garde-fous. Ces observations ne constituent pas un snapshot commun P1/P2.

| Contrôle exécuté | Résultat |
| --- | --- |
| Backend actif | Image lot2 `sha256:3dedda8e3518d5f12ffc65f7765d92310afa46d83b63e006bbec61fdbd619bb2`, running/healthy ; commande, origine, flags, rotation, montages et labels de création conformes. |
| Compose/Caddy | Cinq fichiers et ordre de création conservés ; projection du candidat avec commande explicite mono-processus conforme, sans application. Caddy hôte/bind/config active conformes au lot1. |
| Empreintes Compose | Deux différences brutes qualifiées **localement** : sources CRLF contre VPS LF. Les SHA-256 locaux après remplacement CRLF par LF sont exactement ceux du VPS : base `9619baf702d3987d8290ae4d65f87e7d6d6c918e5d530002db8d20cf0e8f08f4`, Caddy `31bd762f5b6e6a73a0fa78e4a48eec389dd5e2dffa4b7fbc5be1860db8da0fbf`. Trois overrides identiques octet pour octet. Le reçu brut conserve `compose_hashes_match:false`. |
| Mutateurs observables | Un Python/Uvicorn API ; zéro gunicorn/reloader, enfant FFmpeg/FFprobe/yt-dlp/Deno, candidat Python hôte ou autre conteneur mutateur détecté. Zéro fichier partiel ; workspaces0 octet. Threads natifs et writers administratifs/externes non prouvés absents. |
| Réseau et dev | API sans ports publiés, sans réseau/PID hôte ; sockets hôte8000/8001 absents. Dev arrêté, exclu du profil par défaut. **Réserve : bindings de ports historiques toujours enregistrés dans le conteneur dev arrêté** (`dev.no_ports:false`). Pas d'exposition active observée ; ports exacts et politique de redémarrage non exportés. |
| Stockage/NTP | Environ106,40 Gio disponibles sur les filesystems staging/Docker/audio ; audio alloué6,73 Gio, cache global5,77 Gio, ancien trash1,41 Gio ; sync dans le volume audio, inodes disponibles18798657. Sous-totaux non additionnables. NTP synchronisé. Aucune purge. |
| SQL/droits | Base correcte, READ ONLY, SELECT autorisé, visibilité globale complète confirmés.445 jobs : queued0, running0, requires_resolution6, statut inconnu0. Aucun identifiant ni contenu de ligne exporté ; les six jobs ne sont ni annulés ni qualifiés d'obsolètes. |

Preuves expurgées locales : `scratch/sec0034/p1-result.json`, `p2-result.json`,
`p2-executed.sql`, `p2-rights-executed.sql`, `hash-qualification.json` et
`execution-manifest.json`. Le manifeste de préparation est historique et reste intact.
Collecteur exécuté SHA-256 `8c2ab2c442e3d09d8481c989986d7dd410b42be0cd6c64c91161e7895d17b68c` ;
SQL préparé SHA-256 `65b08282bb90e9fc4a6dfd0fbb84db8f2d02429b5790e1b641efb73554afd3b5`.

**Verdict : GO pour préparer la fenêtre, NO-GO pour une bascule immédiate.** Le gate
strict P1 n'est pas entièrement vert tant que la réserve du conteneur dev n'est pas
explicitement traitée : acceptation opérateur de son maintien arrêté sans démarrage
direct, ou inventaire/nettoyage séparément autorisé après contrôle de ses montages,
sans suppression de volume. Aucune de ces actions n'a été exécutée.

Restent obligatoires dans leurs phases autorisées : deux connexions SSH indépendantes,
absence d'autre opérateur/writer, maintenance et drain puis arrêt propre vérifié de
l'ancien backend, validation réelle JWT/téléchargement/DELETE204/streaming/logs du
candidat et sondes extérieures IPv4/IPv6. Aucune de ces validations futures n'est
déclarée réussie par ce préflight. Les dix tests locaux de préparation et les329 tests
SEC-003.3 ne sont pas de nouvelles exécutions pendant P1/P2. Le risque JSON résiduel
reste inchangé. Aucun accès distant supplémentaire après le constat de la réserve.

## Phase T autorisée — arrêt avant toute écriture distante

Après P1/P2, l'opérateur accepte le maintien du conteneur dev historique arrêté et
les écarts CRLF/LF. Il autorise uniquement transfert/import/projection, avec un gate
préalable en lecture seule sur la politique de redémarrage de dev. Maintenance,
bascule, démarrage candidat et réouverture ne sont pas autorisés.

Le premier SSH constate : daemon `unix:///var/run/docker.sock`, dev `exited`,
politique **`unless-stopped`**, MaximumRetryCount0 ; API lot2 toujours running/healthy,
RestartCount0, même conteneur et démarrage `2026-10-18T20:39:39.05730274Z` ; Caddy running.
Le premier prédicat technique exigeait `no` et renvoie donc false : cela ne démontre
pas à lui seul un défaut opérationnel. Selon la [documentation Docker](https://docs.docker.com/engine/containers/start-containers-automatically/),
`unless-stopped` conserve l'arrêt volontaire, même après redémarrage du daemon.

Une seconde lecture est préparée pour confirmer `HasBeenManuallyStopped` dans les
métadonnées Docker locales au VPS, sans exporter le fichier brut ni le modifier.
**La connexion SSH expire avant tout résultat.** Le drapeau n'est donc pas vérifié ;
la lecture de ce fichier n'est pas revendiquée comme exécutée. Aucun nouvel essai
distant après cet échec, conformément au point d'arrêt demandé. Ce timeout ne prouve
ni panne du VPS ni arrêt de l'API ; l'état sain est celui du premier contrôle seulement.

Archive locale revérifiée :296693027 octets, SHA-256
`8e1f7ee55857de3f206d95f5350e32385ae0400ea0ebe7b15f207d98028c8f9e`.
Lecture locale `manifest.json` : une image, tag attendu, configuration OCI
`44d35a2bf52a08ae95ee871fc025b2f4e7569bd4de02342298a1de4c1a701607`.
Inspection supplémentaire Docker Desktop refusée par les permissions locales ;
elle n'est pas une nouvelle preuve d'identité. Les preuves SEC-003.3/P0 restent historiques.

**NO-GO poursuite T pour cette tentative** : préalable distant incomplet. Aucun staging
créé, transfert, hash reçu, import, inspection du candidat VPS ni nouvelle projection
Compose exécutés. Aucune configuration active, volume, secret, conteneur ou image
existant modifié/supprimé ; aucune commande SQL ni mutation des six jobs
requires_resolution. Reçu expurgé local : `scratch/sec0034/phase-t-receipt.json`.

Reprise : obtenir une connexion SSH fonctionnelle puis achever le gate dev en lecture
seule avant toute écriture. Aucune nouvelle autorisation de maintenance n'est implicite.

### Reprise T après confirmation utilisateur du retour SSH

Nouvelle lecture distante réussie : dev arrêté, `RestartPolicy.Name=unless-stopped`,
`HasBeenManuallyStopped=true`, `Restarting=false`. **Le gate Docker d'arrêt volontaire
est maintenant démontré** ; aucune modification de sa politique n'est nécessaire.
Cela couvre le mécanisme de redémarrage Docker, pas un futur démarrage explicite par
un opérateur ou un superviseur externe. Le maintien arrêté accepté reste obligatoire.

Archive locale à nouveau vérifiée : SHA-256 et taille inchangés, une image/tag attendus,
index OCI portant l'ID candidat exact, configuration Linux AMD64, labels source/base,
CMD workers1, workdir `/app`, sans entrypoint ni volume déclaré. Lecture de l'archive
uniquement, sans démarrage du candidat ni accès Docker Desktop requis.

Le lanceur local `scratch/sec0034/phase_t.py` prépare trois étapes séquentielles :
gate complet lecture seule, réception exclusive dans un nouveau staging privé, puis
import et projection six fichiers. Avant toute écriture, il doit confirmer les cinq
hashes distants déjà qualifiés, l'état actif lot2, le daemon Unix attendu, l'arrêt dev
et une projection dont seules l'image/commande changent. Les étapes suivantes ne sont
jamais appelées après un échec. Les fichiers et scripts générés restent locaux.

**Ce second SSH expire pendant l'étape gate, sans résultat distant.** Aucune preuve
de cette nouvelle projection ni de l'état courant complet n'est donc revendiquée.
Arrêt sans réessai après cet échec ; le contrôle réussi précédent ne permet pas de
présumer que le reste a été exécuté. Aucun staging distant, transfert, import, fichier
d'override distant, maintenance ou démarrage. Aucune commande SQL, aucune mutation
des six jobs par cette intervention. La production n'a reçu aucun signal ou changement.

**Verdict inchangé : NO-GO poursuite T pour cette tentative, connexion automatisée
intermittente.** Le point dev est levé ; il reste à obtenir un accès automatisé stable
et à achever le gate complet avant transfert. Reçu expurgé :
`scratch/sec0034/phase-t-resume-receipt.json`. L'autorisation T reste limitée à T ;
aucune autorisation de maintenance/bascule n'en découle.

### Nouvel essai SSH diagnostiqué, 20 octobre 2026

Nouvel essai explicitement demandé par l'opérateur. Client Windows OpenSSH identique
à celui de PowerShell, options BatchMode/StrictHostKeyChecking conservées, verbosité
client activée avec sortie expurgée. Le gate reste exclusivement en lecture seule.
Après27,21s, code255 : connexion TCP établie et protocole SSH distant reçu, mais aucune
authentification réussie ni commande distante envoyée. Aucun marqueur d'offre de clé
observé. Aucun marqueur explicite de timeout/refus réseau/refus de clé d'hôte dans
cet essai. **La cause exacte reste indéterminée** ; ne pas le qualifier de nouveau
timeout TCP, ni de preuve d'un mauvais mot de passe. La première collecte diagnostique
conserve des événements booléens seulement, insuffisants pour identifier l'erreur
terminale. Le collecteur local a ensuite été complété pour retenir une allowlist
expurgée des erreurs SSH/protocole lors d'une éventuelle reprise, sans nouvel essai.

Le contrôle local de configuration effective confirme BatchMode et un seul essai,
sans proxy affiché. L'état du service ssh-agent n'est pas accessible via Get-Service
(service introuvable dans cet environnement) ; cela ne démontre pas la cause de cet
échec antérieur à l'authentification. Aucun changement de configuration locale/distant.

Arrêt après cet échec, aucune réception/import/projection distante ni opération SQL.
Les111 sources candidates sont inchangées ; SHA archive locale à nouveau conforme.
Reçu : `scratch/sec0034/phase-t-diagnostic-receipt.json` ; événements client dans
`t-20261020T200328Z-288c0518/gate-ssh-diagnostic.json` sous le même dossier.
**NO-GO poursuite T pour cette tentative.** Le gate dev confirmé précédemment reste
une observation historique ; le gate complet doit réussir avant toute écriture.



### Échange de bannière : diagnostic confirmé et essai T en connexion unique

L'opérateur fournit deux essais Windows : le premier expire pendant l'échange de
bannière, le second termine avec code0. Le marqueur id_dsa type-1 indique une identité
candidate absente, sans démontrer un échec d'authentification.

Le lanceur local `scratch/sec0034/phase_t_session.py` prépare gate, réception et import
sur une connexion unique : aucune reconnexion automatique, envoi de l'archive seulement
après reçu gate positif et acquittement explicite du lanceur. Aucun changement des
protections SSH. Code distant transmis compressé en mémoire, sans installation de script.

Essai observé : TCP connecté puis `Connection timed out during banner exchange`,
code255 après10,10s (ConnectTimeout10). Aucune authentification ni commande distante ;
le programme gate n'a pas commencé. Cela localise l'échec dans l'échange initial SSH,
mais ne détermine pas sa cause serveur/réseau/intermédiaire. Aucune lecture de logs SSH
serveur ni modification de sshd/pare-feu effectuée. Le chemin de staging inscrit au
reçu est seulement une cible prévue : aucun dossier distant créé.

Reçu expurgé et diagnostic :
`scratch/sec0034/ts-20261020T200929Z-1da601d1/receipt.json` et `ssh-diagnostic.json`.
Archive locale/hash/OCI/configuration de nouveau conformes ;111 sources inchangées.
Vérifications locales du lanceur : syntaxe générée conforme ; gate refusé ou acquittement
absent empêchent tous deux l'appel réception/import (deux scénarios synthétiques).
Aucun test backend ni Supabase réexécuté. **NO-GO poursuite T de cet essai** ; aucun
transfert/import/démarrage, aucune mutation SQL ni changement actif distant.


## Phase T réussie — 21 octobre 2026

T seule autorisée, nouvel essai demandé avec ConnectTimeout30 côté client uniquement.
Aucun réglage SSH serveur/pare-feu/système changé. Une connexion authentifiée, code0,
durée totale gate/transfert/import39,89s ; observation finale **2026-10-21T16:10:23+02:00**.
Ce succès ne prouve ni la cause des échecs précédents ni deux connexions de secours.

| Contrôle effectivement exécuté | Résultat |
| --- | --- |
| Staging neuf privé | `/root/aura-sec0033-staging-20261021T140944Z-cbcf1979`, répertoire0700 et archive0600, création exclusive. Aucun fichier SEC-002.4 écrasé. |
| Archive | `security-lot3-rc-amd64.tar.gz`,296693027 octets ; SHA-256 local, après réception, avant import et final : `8e1f7ee55857de3f206d95f5350e32385ae0400ea0ebe7b15f207d98028c8f9e`. |
| Daemon/import | Endpoint vérifié et explicitement épinglé `unix:///var/run/docker.sock`, identité constante. Docker image load de cette seule archive, un manifeste/tag ; collision de tag refusée, aucune purge. |
| Image inspectée sur VPS | `sha256:cc5fcd84e4c5c58ff095b9f6f290e34f95eddb0d26b5fa36f1998eee73fb60ec`, Linux AMD64, titre `AURA SEC-003.3 candidate`, base lot2 `3dedda8e…619bb2`. |
| Métadonnées | Source-manifest `ba6f6b2b6cab01a4bf1a7fae798cfb807f448be995a3f75221d309a995e004aa`, CMD explicite workers1, workdir `/app`, flagsfalse, aucun entrypoint/volume déclaré. Config OCI archive SHA `44d35a2bf52a08ae95ee871fc025b2f4e7569bd4de02342298a1de4c1a701607` vérifié localement. |
| Override inactif | `/root/aura-sec0033-staging-20261021T140944Z-cbcf1979/compose.candidate.yml`,0600, SHA-256 `96f8c1e38f86913454e792cbd04d0305a041e347c6b810eb9c4d955140e35f10`, image exacte/pull-never/commande workers1. |
| Projection | Cinq fichiers existants hashés et conservés puis sixième override : seules image/commande changent. No-build/pull-never, aucun port API, seul volume audio, flagsfalse et rotation10m/3 conformes. JSON complet capturé en mémoire distante, jamais exporté avec les secrets ; projection non appliquée. |
| Service actif | Backend lot2 running/healthy, même ID et StartedAt, RestartCount0 avant/après. Caddy même ID/démarrage, running et hash inchangé. Inventaire de tous les conteneurs identique, aucun conteneur candidat créé. |
| Dev | exited, unless-stopped et HasBeenManuallyStopped=true, jamais démarré ; anciens bindings acceptés par l'opérateur, maintien arrêté obligatoire. |

Reçu de référence : `scratch/sec0034/phase-t-transfer-receipt.json` ; preuves par étape
dans `scratch/sec0034/ts-20261021T140944Z-cbcf1979/`, empreintes et programme exact inclus.
Les reçus d'échec précédents restent historiques. Aucun accès distant supplémentaire
après T. Aucune modification de configuration active/Caddy/volume/secret/rôle/code
métier/version cliente/candidat figé ; aucune image/conteneur/fichier existant supprimé.
Aucune commande SQL : les six requires_resolution de P2 n'ont pas été mutés par cette
intervention ; leur nombre actuel n'a pas été recompté. Les111 sources restent identiques.
Les tests backend/session réelle Supabase n'ont pas été réexécutés pendant T.

**Verdict : GO préparation maintenance seulement. M/D/V/R ne sont pas autorisés.**
Avant M : deux SSH indépendants et absence d'autre writer à vérifier, activité courante
à recontrôler. Pendant M : maintenance, drain, SIGTERM sans SIGKILL automatique puis
confirmation de la fin native selon les gates précédents. Après D : validations JWT,
téléchargement, DELETE204, streaming, logs et sondes réseau extérieur avant R.
Aucun ancien backend vulnérable ne sera réexposé pour tenir un délai. Risque JSON
résiduel et cache sans éviction inchangés. Aucun commit/push ni migration.


## Fenêtre autorisée — prérequis exécutés, décision avant coupure

L'opérateur autorise la suite M/D/V/R par « go pour appliquer la suite ! » ; aucune
migration SQL n'est requise par SEC-003.3 et aucune migration n'est effectuée.
Deux canaux SSH indépendants sont établis et réinterrogés, plus un login neuf externe.
Compte dédié historique : login/refresh, JWT ES256 vérifié JWKS et logout réussis ;
aucun identifiant/token/cookie publié. SQL READ ONLY :446 jobs,0queued/0running,
6requires_resolution, droits de lecture complète confirmés ; aucune mutation.
Baseline image/Compose/Caddy/dev/NTP/espace/processus conforme ; seule l'autre session
opérateur Python est exclue nominativement des candidats writers hôte. Sondes HTTPS
health200/privé401/test404/dev503, aucun HTTP direct8000/8001 ; réserve IPv6 conservée.

**329 tests,0 ignoré,12,44s** relancés dans l'image exacte sur le VPS : conteneur jetable
sans réseau, ports, montages ou credentials de production, racine read-only,1G RAM/swap
bornée et /tmp tmpfs ; suppression du seul conteneur de test. Production lot2 inchangée.
Ces tests incluent les non-régressions SEC-003 ; ils ne prouvent pas YouTube en production.

Pour la sonde métadonnées, source publique Creative Commons du corpus officiel yt-dlp :
https://www.youtube.com/watch?v=M4gD1WSo5mA (référence :
https://raw.githubusercontent.com/yt-dlp/yt-dlp/master/yt_dlp/extractor/youtube/_video.py).
Deux lectures anonymes, puis une lecture avec les options configurées, sans télécharger
ni créer de job : DownloadError de catégorie provider_auth_required. L'usage limité des
cookies existants avait été refusé par l'auto-review, puis spécifiquement autorisé par
l'utilisateur pour cette seule vidéo, avec copie /dev/shm et original inchangé. La sonde
ensuite approuvée constate **aucun fichier de cookies configuré** : aucun cookie lu,
copié ou utilisé, aucun secret modifié/exporté. Ce diagnostic ne démontre pas un défaut
du candidat SEC-003 : le fournisseur refuse déjà la source avant déploiement.

**Maintenance non commencée, backend non recréé, aucun reload/signal.** Le positif natif
prévu n'est pas exécutable avec l'accès fournisseur présent. Rapport expurgé :
`scratch/sec0034/window/premaintenance-report.json` et reçus voisins. Scripts d'exécution
maintenance/arrêt propre/bascule six fichiers/repli fermé préparés mais non exécutés.

### Proposition de validation ajustée, en attente de décision

Ne pas modifier/installer de cookies, contourner un contrôle fournisseur ni corriger
l'image figée. Après accord explicite sur cette limite : M puis D selon les gates
existants ; V conserve JWT ES256 réel/login/refresh/logout, streaming synthétique
200/206/header/query, négatifs401 et /test404, vérification110 fichiers embarqués,
mono-processus, ports/logging/rotation/volumes/dev et contrôles extérieurs.
Deux nouveaux jobs seulement du compte dédié, IDs frais et même vidéo publique :
POST201 et GET /jobs réels ; constater le résultat fournisseur, DELETE204 vide puis
404, annulation d'un second job réellement queued/running si observé, témoin littéral
préservé et fin du workspace natif avant nettoyage limité aux fixtures créées.
Si le fournisseur refuse encore : **aucun succès de téléchargement natif déclaré**.
Le streaming peut être démontré séparément avec les seuls fichiers synthétiques.
Réouverture possible uniquement si l'opérateur accepte cette limite de couverture
et si tous les autres gates critiques passent. Tout autre échec maintient la
maintenance et nécessite une décision ; aucun retour au backend vulnérable.
Sans acceptation de ce protocole, suspension avant coupure pour rétablir d'abord un
accès fournisseur adapté. Aucun changement de secret n'est implicitement autorisé.


### Décision opérateur sur le protocole ajusté

Le 2026-10-21T16:36:45+02:00 : réponse explicite « Autoriser ce protocole ajusté ».
M/D/V/R autorisés avec tous les gates inchangés, sauf succès natif YouTube pouvant
rester non validé si seul le refus fournisseur préexistant persiste. Aucun ajout de
cookie ni changement de secret autorisé. Tout autre échec interdit la réouverture.


## Point d'arrêt de la fenêtre — candidat sain, maintenance conservée

Maintenance activée2026-10-21T14:36:58.355728Z, sauvegarde Caddy vérifiée/inode conservé,
JSON actif exact ;503 extérieur passé, SSH neuf et ports confinés. SQL sous maintenance :
0queued/0running/6requires_resolution/446total. Ancien backend arrêté proprement à
14:37:52.219835379Z : exited/code0/PID0/OOMfalse, SIGTERM timeout Docker-1, aucun SIGKILL.

La recréation ciblée aura-api avec six fichiers/imagecc5fcd84 a eu lieu ; le lanceur
échoue ensuite avec operation_command_failed, sans conserver la commande précise.
**Cause initiale non établie**, reçu de fin absent. Aucun nouvel up/restart/reload ou
rollback ensuite. Lecture de secours : candidat running/healthy/RestartCount0,110
empreintes et manifeste conformes, Python3.12.15/SDK2.33.0. Caddy maintenance exact,
inode conservé, dev arrêté, aucun port API publié.

Le vérificateur original parcourt /app donc aussi /app/downloads monté : défaut de
périmètre confirmé, mais cause seulement hypothétique de l'échec initial (stderr non
conservé). Il passe inchangé isolé sans volume production. Variante en mémoire seulement :
`for folder in ('/app', '/opt/aura-validation'):` devient
`for folder in ('/app/app', '/opt/aura-validation'):`.
Les110 empreintes couvrent aussi README/pyproject ; SHA du script et du manifeste
épinglés avant contrôle. Imports/dépendances/pip check passent ; aucun fichier image ou
bibliothèque modifié. Un premier diagnostic a échoué à parser la ligne pip check avant
le JSON ; parseur local corrigé au dernier JSON puis vérification réussie. Ce défaut de
collecte n'est pas une régression du backend.

Postcheck : un Python/Uvicorn, six labels Compose, volume unique, flagsfalse/logs10m3,
ports sûrs/dev arrêté ; santé200, anonyme401/invalide401/test404. Aucun job réel ni
réouverture encore exécuté. Reçu : `scratch/sec0034/window/maintenance-stop-report.json`.
Opération `/root/aura-sec0034-operation.hclub2d3`, wrapper futur `compose-release.sh` ;
ne supprimer ni ce dossier ni le staging SEC-003.3 référencé. Aucun SQL en écriture,
cookie/secret/version/client modifié.

### Reprise proposée à autoriser après ce point d'arrêt

Garder le candidat sain actuel sans reconstruction/recréation/redémarrage. Retenir les
vérifications110 fichiers/manifeste/imports/dépendances et original isolé déjà réussies.
Continuer V : sessions/streaming dédiés, deux jobs/DELETE204 et témoin selon le protocole
fournisseur ajusté accepté, puis logs synthétiques. R uniquement après tous les autres
gates ; tout autre échec maintient la maintenance et impose une nouvelle décision.
Aucun retour au backend vulnérable.


### Reprise explicitement autorisée

2026-10-21T16:48:18+02:00 : « Oui, terminer et rouvrir si conforme ». Reprendre V/R depuis
le candidat actuel sans reconstruire/redémarrer. Les contrôles ciblés110 fichiers et
l'original isolé sont retenus ; seule l'exception fournisseur YouTube reste acceptée.


## Livraison finale — fenêtre terminée


Production rouverte le **2026-10-21T16:51:44+02:00**.
Maintenance : 2026-10-21T16:36:58+02:00 à
2026-10-21T16:51:44+02:00, **886.05s (~14min46s)**,
pauses de décision incluses. Audit final : 2026-10-21T14:53:43.361083+00:00.

Image active Linux AMD64 : `sha256:cc5fcd84e4c5c58ff095b9f6f290e34f95eddb0d26b5fa36f1998eee73fb60ec`.
Archive SHA-256 : `8e1f7ee55857de3f206d95f5350e32385ae0400ea0ebe7b15f207d98028c8f9e`.
Manifeste source : `ba6f6b2b6cab01a4bf1a7fae798cfb807f448be995a3f75221d309a995e004aa`.
Python3.12.15, Supabase2.33.0 ;110 empreintes embarquées conformes.

## Autorisations

- T autorisée séparément et terminée avant la fenêtre.
- « go pour appliquer la suite ! » autorise M/D/V/R, avec points d'arrêt.
- Sonde cookies initialement refusée par auto-review, puis usage limité explicitement
  autorisé. Aucun cookie n'était configuré : aucun cookie lu/copié/utilisé/modifié.
- Protocole de couverture YouTube ajusté autorisé, puis « Oui, terminer et rouvrir si
  conforme » après le point d'arrêt du lanceur. Le test natif complet a finalement
  réussi ; aucune dérogation fournisseur n'est utilisée pour le verdict final.



Résultats finaux :329/329 tests VPS,110 hashes/SDK/pip, vrai téléchargement succeeded,
fichier/stream200/206, DELETE204 vide d’un job actif sans résurrection, témoin intact,
deux jobs nettoyés ; SQL446/0queued/0running/6requires_resolution après fixtures.
JWT réel ES256/refresh/logout et streaming public vérifiés ; logs expurgés et confinement
conservés. Deux SSH fermés proprement après audit final. Aucun secret/cookie/version
client/migration changé, aucun commit/push. Le refus des sondes métadonnées (composants
JS désactivés dans ces sondes) n’est pas une panne démontrée du parcours complet : le
positif natif réel a réussi. L’exception fournisseur autorisée n’est donc pas utilisée.

Rapport : `scratch/sec0034/window/execution-report.md`, JSON et reçus voisins.
**Utiliser dorénavant `/root/aura-sec0034-operation.hclub2d3/compose-release.sh`**, six
fichiers ; conserver aussi le staging lot2 référencé. L’ancien wrapper lot2 est historique,
ne plus l’utiliser pour récréer le backend. Pas d’autre opération autorisée par le bilan.
Actions restantes : test UI rapide Android/Desktop/Web ; aucun rebuild/migration requis.
Limites : IPv6 extérieur non concluant, pas de nouveaux JWT réels HS256/RS256 ni attente
naturelle d’expiration, JSON non atomique après réassignation, cache sans éviction.
Le lanceur a eu un échec non attribué précisément ; reprise explicitement autorisée
après vérifications ciblées et originales isolées. Aucun changement du candidat figé.
