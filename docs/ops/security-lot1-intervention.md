# Intervention opérationnelle du lot 1

## Périmètre et décision

Ce document suit SEC-001.3 à SEC-001.6 : confinement de l'ancien backend, puis activation du lot 1.
Les candidats ont été installés et A+B exécutée sur autorisation ; B est déployée. Toute copie distante, modification
de règle, recréation, arrêt, reload ou bascule exige l'autorisation de l'opérateur pour l'étape nommée.
Les secrets, données, versions clients et migrations restent hors intervention.

**Décision courante — A+B terminée, 2026-10-17 :** après le point d'arrêt ci-dessous,
l'opérateur a autorisé le diagnostic et la reprise par « oui ». Caddy est reparti uniquement
avec la maintenance vérifiée, puis tous les gates A+B ont réussi. Image B déployée, prod
réouverte à 19:44:42+02:00, contrôles finaux à 19:46+02:00 ; dev reste arrêté/503. L'interruption
API depuis 19:34:09 dure environ 11 minutes, pause opérateur comprise. Six règles temporaires
retirées après dépublication, aucun socket hôte 8000/8001 ; debug/recovery/backfill false,
logs sûrs et rétention 10m/3 sur prod/Caddy. Session réelle et refresh via Caddy, streaming
200/206, refus 401 et /test 404 vérifiés, quatre fichiers temporaires supprimés et reçus contrôlés.
La réserve IPv6 préexistante demeure, sans intervention réseau supplémentaire.

**Historique du point d'arrêt initial :** après P0, l'opérateur
a autorisé la fenêtre A+B par « ok go », interruption d'environ 30 minutes acceptée et
prolongation possible sans réexposition des bypass. Sauvegarde vérifiée, candidats installés,
six règles temporaires présentes et accès directs extérieurs sans réponse HTTP. La bascule
Caddy a échoué sur une assertion du contrôle opérateur : arrêt de secours Caddy exécuté
à 19:34:09+02:00, état exited confirmé. Le fichier de maintenance est en place avec le bon
hash et l'inode conservé, mais l'activation attendue n'est pas confirmée. Ne pas attribuer
prématurément l'échec à Caddy ou au correctif Auth ; la cause exacte reste à qualifier.
Les backends prod/dev gardent leurs conteneurs/images antérieurs et sont encore running,
sans accès public API constaté ; ni arrêt dev, ni recréation, ni B ne sont exécutés.
HTTPS prod/dev est indisponible, pas en 503. Les six règles et le journal restent conservés,
les deux SSH fonctionnent. **Attendre une décision de reprise après ce point d'arrêt** ;
aucune remise en route ni restauration de l'ancien proxy n'est déduite de l'autorisation initiale.
Les secrets, migrations, volumes et versions sont inchangés.
La séquence coordonnée et ses autorisations ci-dessous prévalent sur les décisions antérieures.
Après clarification du rôle de VNC, son test n'est plus un prérequis obligatoire de cette
intervention : SSH chiffré reste le canal normal, avec deux connexions indépendantes et des
reconnexions aux gates. VNC est un secours facultatif, à utiliser seulement par un accès
protégé ; une connexion annoncée non chiffrée ne doit pas servir à saisir le mot de passe root.
Si le périmètre venait à toucher SSH, les politiques générales du pare-feu ou le réseau hôte,
réévaluer le secours hors bande avant d'autoriser cette extension.

## Diagnostic et invariants

L'audit du 2026-10-17 confirme : UFW actif, backend Docker iptables, chaîne DOCKER-USER sans
règle, DNAT des ports 8000/8001, réseau commun `infra_default`, Compose 5.1.2 et Engine 29.4.0.
Caddy communique par DNS Docker avec les API ; il n'utilise pas leurs ports publiés sur l'hôte.
Le Compose du dépôt publie aussi le provider sur 4416 ; cette publication est une observation
distincte, conservée dans la proposition minimale limitée aux ports API 8000/8001.
Une règle UFW INPUT seule ne protège pas les ports publiés Docker, dont le trafic est redirigé
avant ces règles. Ne pas désactiver iptables dans Docker et ne pas modifier les chaînes générées.

Retirer les publications ne ferme pas les bypass accessibles via HTTPS. Le confinement complet
comprend une maintenance Caddy n'autorisant que GET/HEAD /health, puis la suppression des accès
directs, et l'arrêt de dev. Cela coupe volontairement la lecture en ligne, la sync, les téléchargements
et les autres routes, dont OTA ; la lecture locale déjà disponible reste indépendante du backend.
Une simple protection des ports/logs sans maintenance n'est pas annoncée comme confinement des bypass.

Le service dev partage projet Supabase, clé privilégiée et stockage prod. Ses tâches de recovery
et backfill peuvent écrire au démarrage ; ne pas le recréer ni l'utiliser comme préproduction.
Le garder arrêté, hors dépendances Caddy et hors profil Compose par défaut. Un futur environnement
de test nécessite un autre projet, des credentials dédiés et un stockage distinct.

## Changements candidats

- `infra/security-lot1/compose.containment.yml` : suppression réelle des ports hérités avec
  `!reset []` (8000 et 8001), APP_DEBUG=false, tâches de recovery/backfill suspendues
  pendant la transition, lancement avec journalisation structurée, dev désactivé par profil,
  Caddy dépendant seulement de prod. Deux fichiers de logging sont montés en lecture seule
  pour permettre cette protection sur l'ancienne image pendant la maintenance. L'image de
  confinement est sélectionnée par un tag local vérifié, sans pull ni build implicite.
- `infra/security-lot1/Caddyfile.containment` : maintenance prod et dev, santé prod seulement,
  traces filtrées et identifiants de requête générés côté proxy.
- `infra/security-lot1/compose.release.yml` : image locale vérifiée exigée explicitement,
  retrait des deux montages de transition puisque le logging est embarqué dans l'image.
- `infra/security-lot1/Caddyfile.release` : prod réouverte seulement après les contrôles B,
  dev maintenu en 503 ; ne jamais l'ouvrir contre les ressources de production.
- `server/app/core/operational_logging.py`, `security-logging.json` : formateur JSON à liste
  positive, contexte d'identifiant de requête, conservation des statuts/emplacements d'erreur,
  exclusion de tout message, argument, champ extra, valeur d'exception ou ligne source.
  Capture des warnings Python ; les sorties natives hors logging restent une limite distincte.
- `server/app/services/download_service.py` : rediriger les messages yt-dlp vers logging,
  afin d'éviter ses warnings/erreurs directs sur stderr.
- `infra/docker/server.Dockerfile` : même construction Python 3.12 ; CMD utilisant le lanceur
  et la configuration sûrs embarqués. Aucun changement de version d'application.

Les filtres Caddy 2.8.4 ne traitent pas `msg` : `fields { msg delete }` est inefficace.
Les candidats suppriment message/stacktrace libres via les clés de l'encodeur JSON et nettoient
les champs structurés. Les causes textuelles ne sont donc pas disponibles dans les logs de
confinement ; niveau/logger/horodatage, méthode/chemin sans query, statut/durée et identifiants
restent utiles au diagnostic. Ne pas réactiver debug ou logs bruts pour diagnostiquer un échec.
Le profil de logging couvre les handlers Python et les messages yt-dlp redirigés vers eux,
pas toute écriture native arbitraire future vers stdout/stderr. Les tests ne démontrent pas
l'absence de fuite dans un binaire externe ou un collecteur non inspecté. Ne pas donner une
garantie globale sur ces canaux ; vérifier les sorties effectives avant la réouverture.

Le Compose candidat conserve le montage Caddy existant. La copie du contenu maintenance/release
vers `infra/caddy/Caddyfile`, suivie d'un reload explicite, est une opération distincte ; lancer
le Compose release ne doit jamais réouvrir automatiquement les routes avant leurs tests.
Écraser le contenu du fichier monté en conservant son inode : ne pas remplacer ce fichier
par un `mv` atomique, qui peut laisser le conteneur attaché à l'ancien inode.

### Compléments de robustesse avant A

Le Compose candidat borne désormais les logs de prod et Caddy : `json-file`, `max-size: "10m"`,
`max-file: "3"`, soit environ 30 Mo par conteneur, hors dépassement d'une entrée et métadonnées.
Ce n'est pas une durée de conservation garantie. L'ancien dev reste arrêté ; ne pas le recréer
pour appliquer une rétention. Ne pas supprimer/tronquer manuellement les fichiers Docker.
Un reload Caddy ne change pas son LogConfig : une recréation ciblée Caddy, avec ses volumes
de certificats conservés, est nécessaire pour appliquer la limite. Vérifier ensuite le LogConfig
des deux conteneurs ; ne pas activer durablement les access logs sans cette limite.

Le script réseau exige maintenant un journal absolu dans un dossier root 0700, par exemple
`$A_BACKUP/ports.json`. Chaque opération reçoit un commentaire UUID distinct, vérifié absent
avant insertion. L'intention de chaque insertion est écrite atomiquement et synchronisée avant
l'appel noyau. Un échec laisse les protections déjà posées : lancer `status` avec le même journal,
sans relancer `apply` avec un nouveau nom. L'inventaire couvre les six règles : IPv4 INPUT,
IPv4 DOCKER-USER 8000/8001, puis leurs équivalents IPv6. `remove` ne traite que les tentatives
de cette opération, continue sur les autres règles si une famille échoue, retourne un échec
si un état est inconnu, et peut être relancé. Il ne dépend plus des routes par défaut au retrait.
Ne jamais faire de flush ni restaurer globalement un ancien `iptables-save` sur les règles Docker.
Garder le journal jusqu'à confirmation de l'absence des six règles et des publications ; un
journal perdu/corrompu exige un inventaire manuel des commentaires exacts, sans retrait global.

Avant toute mutation, conserver une session SSH interactive de secours sur le poste de l'opérateur,
ouvrir une seconde connexion indépendante avec BatchMode et keepalive, puis confirmer le port
serveur (22 constaté) et l'absence d'autre
intervention réseau. Les règles proposées ne visent que TCP 8000/8001 entrant sur eth0 ; aucun
ACCEPT/flush/politique de chaîne ni règle SSH n'est modifié. Un nouveau login SSH indépendant
est néanmoins obligatoire après chaque étape A. Une session existante seule ne prouve pas
qu'une reconnexion fonctionne. Si elle échoue : STOP, conserver la session ouverte et diagnostiquer
depuis celle-ci ; ne pas poursuivre une recréation ni retirer aveuglément les protections.
La console fournisseur reste un secours facultatif si un accès protégé est disponible.

## Ordre d'intervention après autorisation

### A — Confinement

1. Confirmer la fenêtre de maintenance, l'impact des jobs/transferts interrompus, l'accès SSH
   et l'état des volumes. Copier seulement les candidats approuvés, sans écraser le code Auth
   du dépôt distant ; l'image B sera transférée séparément. Conserver les configurations existantes dans un emplacement
   protégé ; ne jamais copier `.env` ou des journaux vers un rapport. Ne pas faire `compose down`.
2. Pour fermer immédiatement les accès directs avant recréation, préparer un filtrage temporaire
   DOCKER-USER limité à l'interface publique et aux ports de destination d'origine 8000/8001.
   Utiliser conntrack `--ctorigdstport`, car le port 8001 est déjà traduit vers 8000 à ce stade.
   L'interface constatée pour IPv4 et IPv6 est `eth0`, les deux chaînes DOCKER-USER existent.
   Le script candidat `ports-temporary.sh` ajoute aussi une garde INPUT des deux ports, pour
   couvrir notamment un proxy IPv6 terminant sur l'hôte. Il ne filtre pas SSH, 80/443 ou les bridges,
   porte un commentaire par opération et retire uniquement ses propres règles. Réexaminer les interfaces
   si l'état change. Autorisation pare-feu spécifique requise ; la suppression Compose est durable.
3. Valider Caddy et le modèle Compose sans afficher les secrets.
   Basculer Caddy en maintenance, puis le recharger après validation. Vérifier 503 sur une route
   synthétique et /test, santé 200, dev 503. Les publications directes doivent déjà être bloquées.
4. Arrêter dev sans toucher aux volumes. Recréer seulement prod sous maintenance avec l'image
   de confinement approuvée, APP_DEBUG=false et logging sûr, ports supprimés ; ne pas reconstruire
   implicitement, ne pas démarrer les dépendances/profils. Les flags de recovery/backfill sont
   désactivés pendant cette transition pour éviter des mutations automatiques non approuvées.
   Recréer ensuite Caddy seul pour appliquer la rétention, avec le même ID d'image vérifié,
   les mêmes volumes de certificats et le Caddyfile de maintenance déjà installé.
5. Vérifier absence de publications et accès internes Caddy→prod. Les réseaux et volumes sont
   conservés. Retirer uniquement les règles temporaires introduites à l'étape 2 après vérification,
   avec autorisation pare-feu ; ne pas restaurer les publications pour résoudre un incident.

La période A reste une maintenance : aucune réouverture de l'ancien code, même derrière HTTPS.
Le service dev existant doit être explicitement arrêté ; un profil Compose n'arrête pas un
conteneur déjà lancé. Ne pas exécuter `--profile disabled-dev up` : son projet/stockage restent
partagés. Ne pas utiliser `--remove-orphans`, `down`, `down -v` ni une commande globale `up`.

### B — Déploiement sécurisé

1. Construire localement l'image lot 1 à partir d'un contexte explicitement autorisé, sans `.env`,
   credentials, cookies ni volumes. Conserver ID d'image, plateforme, dépendances et empreintes
   des sources Auth/routeur/logging ; un tag seul ne constitue pas une identité immuable.
2. Exécuter les tests synthétiques et la session Supabase réelle dédiée décrite dans
   `docs/server/security-and-secrets.md`. Confirmer le mode actif et les signatures anciennes :
   JWKS ES256 publié n'implique pas que toutes les sessions soient ES256. HS256 exige un secret
   serveur adéquat ou une reconnexion/refresh après migration autorisée vers une clé asymétrique.
   Aucun changement de clé ou de secret n'est implicitement autorisé.
3. Après validation, autoriser explicitement transfert d'image et recréation prod sous maintenance.
   Vérifier sur la cible ID, hash Auth, debug, logs et absence de routes /test. Ne pas démarrer dev.
4. Tester refus invalides et accès/streaming/refresh d'un compte dédié, sans créer de données
   persistantes de production. Une session d'un autre projet ne valide pas l'issuer de prod.
   Préparer l'autorisation d'un compte de test de production sans données si nécessaire ; sans
   celle-ci, ne pas prétendre avoir validé la vraie session contre prod.
5. Autoriser séparément le reload Caddy vers `Caddyfile.release`. Vérifier disponibilité HTTPS,
   authentification et streaming/Range, 404 /test, dev 503, ports directs fermés et absence de
   marqueurs synthétiques dans toute la chaîne, sans copier les journaux.
6. La reprise de recovery/backfill reste une décision distincte : ces routines écrivent des
   données et peuvent traiter les jobs interrompus. Les candidats les laissent désactivées ;
   ne pas prétendre que la réouverture a réparé ou repris automatiquement les anciens jobs.

## Commandes proposées et critères de réussite

Ces commandes sont un runbook à approuver, pas des opérations exécutées par cette préparation.
Se placer dans `/opt/aura`, conserver le projet Compose `infra` et les trois fichiers dans
l'ordre suivant. `config --quiet` valide le modèle sans imprimer l'environnement ou les secrets.

```bash
cd /opt/aura
dc() {
  docker compose -p infra \
    -f infra/docker-compose.vps.yml \
    -f infra/docker-compose.vps.caddy.yml \
    -f infra/security-lot1/compose.containment.yml "$@"
}
dc config --quiet
```

Après vérification que l'image actuellement déployée correspond à l'empreinte attendue :

Créer d'abord la sauvegarde sur le VPS, sans exporter son contenu. Toutes les commandes
ci-dessous sont conditionnées au GO et à la fenêtre acceptée. Conserver `A_BACKUP` dans la
session ; après reconnexion, reprendre son chemin exact, sans générer une seconde opération.

```bash
umask 077
A_BACKUP=$(mktemp -d /root/aura-sec0013.XXXXXXXX)
test -n "$A_BACKUP" && test -d "$A_BACKUP"
export A_BACKUP
test -f infra/caddy/Caddyfile && test ! -L infra/caddy/Caddyfile
stat -c '%d:%i' infra/caddy/Caddyfile > "$A_BACKUP/Caddyfile.inode"
cp --preserve=mode,ownership,timestamps infra/caddy/Caddyfile "$A_BACKUP/Caddyfile.original"
cp infra/security-lot1/Caddyfile.containment "$A_BACKUP/Caddyfile.maintenance"
chmod 600 "$A_BACKUP"/*
(cd "$A_BACKUP" && sha256sum Caddyfile.original Caddyfile.maintenance > Caddyfile.sha256)
(cd "$A_BACKUP" && sha256sum --check --status Caddyfile.sha256)
cmp -s infra/caddy/Caddyfile "$A_BACKUP/Caddyfile.original"
```

STOP si l'une échoue. Confirmer via `docker inspect --format` ciblé que la source du bind est
`/opt/aura/infra/caddy/Caddyfile`, destination `/etc/caddy/Caddyfile`, lecture seule. Comparer
le SHA-256 du fichier hôte à celui lu par `docker exec aura-caddy sha256sum /etc/caddy/Caddyfile`
avant ET après écriture. Seuls des hashes de configuration sont retournés, aucun contenu.

```bash
docker image tag sha256:ef1c466d470cec4d6f66542881c5194c596985246e8c32e62fd10c3081958fae aura-security-lot1:containment-prelot1-20261017
sh infra/security-lot1/ports-temporary.sh apply "$A_BACKUP/ports.json"
sh infra/security-lot1/ports-temporary.sh status "$A_BACKUP/ports.json"
docker exec -i aura-caddy caddy validate --config /dev/stdin --adapter caddyfile < infra/security-lot1/Caddyfile.containment
cat infra/security-lot1/Caddyfile.containment > infra/caddy/Caddyfile
sync -f infra/caddy/Caddyfile
test "$(stat -c '%d:%i' infra/caddy/Caddyfile)" = "$(cat "$A_BACKUP/Caddyfile.inode")"
cmp -s infra/caddy/Caddyfile "$A_BACKUP/Caddyfile.maintenance"
docker exec aura-caddy caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
docker exec aura-caddy caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile
dc --profile disabled-dev stop aura-api-dev
test "$(docker inspect --format '{{.State.Running}}' aura-api-dev)" = false
test "$(docker inspect --format '{{.State.Status}}' aura-api-dev)" = exited
dc up -d --no-deps --no-build --pull never --force-recreate aura-api
# Après santé prod et tests extérieurs maintenance, vérifier que caddy:2.8-alpine
# pointe toujours vers l'ID Caddy audité ; ne pas pull une nouvelle image.
dc up -d --no-deps --no-build --pull never --force-recreate caddy
```

Ces blocs ne sont pas un script à coller d'une traite : appliquer les points d'arrêt ci-dessous.
La commande `stop` explicitement ciblée fonctionne avec le profil ; le profil est nommé pour
éviter toute ambiguïté de version. Contrôler aussi les labels Compose `project=infra`,
`service=aura-api-dev` avant arrêt. Ni code retour zéro seul, ni `compose ps` sans profil ne
prouvent l'arrêt : exiger les deux tests Docker ci-dessus, puis les répéter en fin de A.

Ne pas exécuter le `up` si validation Caddy, filtrage externe ou contrôle d'image échoue.
Si la mise en maintenance échoue, l'ancien backend reste exposé via HTTPS malgré la fermeture
des ports directs. L'arrêt de secours de Caddy (`docker stop aura-caddy`) doit être autorisé
explicitement dans la fenêtre A pour garantir la coupure ; il retire aussi la santé et OTA.
Sans cette autorisation, arrêter l'intervention et signaler le confinement incomplet.
Les validations requises après A sont : santé HTTPS 200, routes API et dev 503 ; aucune
publication de prod et dev arrêté ; `APP_DEBUG=false`, commandes de logging attendues ;
Caddy joint encore `http://aura-api:8000/health`. Inspecter les champs Docker ciblés uniquement,
jamais un `docker inspect` brut contenant l'environnement. Exemple de contrôle interne :

```bash
docker exec aura-caddy wget -qO- http://aura-api:8000/health
```

Depuis un poste extérieur, les deux appels directs doivent échouer sans réponse HTTP :

```powershell
curl.exe --noproxy "*" --connect-timeout 3 --max-time 5 http://212.90.121.80:8000/health
curl.exe --noproxy "*" --connect-timeout 3 --max-time 5 http://212.90.121.80:8001/health
curl.exe --fail --max-time 5 https://aura-prod.duckdns.org/health
```

Vérifier également IPv6 depuis une origine disposant d'IPv6 si le VPS est routable en IPv6 ;
un échec dû à l'absence de réseau IPv6 côté testeur n'est pas une preuve de filtrage.
Une fois les ports réellement dépubliés et contrôlés, retrait nominatif des seules règles temporaires :

```bash
sh infra/security-lot1/ports-temporary.sh remove "$A_BACKUP/ports.json"
sh infra/security-lot1/ports-temporary.sh status "$A_BACKUP/ports.json"
```

Pour B, après transfert/import d'image autorisé et comparaison d'ID avec le manifeste livré :

```bash
export AURA_SECURITY_IMAGE=aura-security-lot1:prepared-20261017-amd64
dcb() {
  docker compose -p infra \
    -f infra/docker-compose.vps.yml \
    -f infra/docker-compose.vps.caddy.yml \
    -f infra/security-lot1/compose.containment.yml \
    -f infra/security-lot1/compose.release.yml "$@"
}
dcb config --quiet
dcb up -d --no-deps --no-build --pull never --force-recreate aura-api
```

Caddy reste en maintenance. Vérifier localement dans le conteneur sans credential :

```bash
docker exec aura-api python -B -c 'import urllib.request,urllib.error; r=urllib.request.urlopen("http://127.0.0.1:8000/health",timeout=5); assert r.status==200; print("health: ok")'
```

Les contrôles `/test` 404 et route privée 401 doivent passer avant un vrai compte. Les scénarios
de session restent dans la procédure canonique, via un outil qui garde les credentials en mémoire,
pas par des JWT sur la ligne de commande. Si ces preuves réussissent et si la bascule est autorisée :

```bash
docker exec -i aura-caddy caddy validate --config /dev/stdin --adapter caddyfile < infra/security-lot1/Caddyfile.release
cat infra/security-lot1/Caddyfile.release > infra/caddy/Caddyfile
sync -f infra/caddy/Caddyfile
test "$(stat -c '%d:%i' infra/caddy/Caddyfile)" = "$(cat "$A_BACKUP/Caddyfile.inode")"
cmp -s infra/caddy/Caddyfile infra/security-lot1/Caddyfile.release
docker exec aura-caddy caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
docker exec aura-caddy caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile
```

Critères B : TLS valide, santé 200, /test 404, invalide/expiré 401, session représentative 200,
refresh réussi, streaming complet 200 et Range 206 avec octets attendus, dev 503, ports fermés.
Les essais de logs n'emploient que des marqueurs synthétiques ; garder les journaux sur place et
retourner seulement les résultats de présence/absence et les compteurs. Une absence de trafic
authentifié dans un ancien échantillon n'est pas une preuve de rédaction.

## Retour arrière et coupures

Pendant A ou en cas d'échec B, revenir au **Caddyfile de maintenance filtré**, garder les ports
fermés et dev arrêté. Les volumes ne sont ni effacés ni restaurés et aucune migration n'est requise.
L'ancienne image n'est utilisable que derrière cette maintenance, jamais comme repli public.
Une réouverture exige un artefact de repli vérifié supprimant également les bypass et /test ;
en son absence, rester en maintenance et réparer l'image. Ne pas revenir au Compose exposant
8000/8001, au debug ou aux logs bruts pour accélérer un retour.

Restauration contrôlée du Caddyfile, en place et sans changer le bind mount :

```bash
(cd "$A_BACKUP" && sha256sum --check --status Caddyfile.sha256)
test "$(stat -c '%d:%i' infra/caddy/Caddyfile)" = "$(cat "$A_BACKUP/Caddyfile.inode")"
docker exec -i aura-caddy caddy validate --config /dev/stdin --adapter caddyfile < "$A_BACKUP/Caddyfile.maintenance"
cat "$A_BACKUP/Caddyfile.maintenance" > infra/caddy/Caddyfile
sync -f infra/caddy/Caddyfile
test "$(stat -c '%d:%i' infra/caddy/Caddyfile)" = "$(cat "$A_BACKUP/Caddyfile.inode")"
cmp -s infra/caddy/Caddyfile "$A_BACKUP/Caddyfile.maintenance"
docker exec aura-caddy caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
docker exec aura-caddy caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile
```

Recomparer les hashes hôte/conteneur et les réponses extérieures. `Caddyfile.original` est une
sauvegarde de diagnostic, pas un repli public autorisé : il réexposerait les bypass. Si inode,
hash, validation ou reload échoue, STOP ; aucun `mv`, aucune restauration de l'ancien proxy.
Le processus Caddy déjà chargé reste sur sa configuration précédente jusqu'au reload réussi ;
si la maintenance n'est pas obtenue, l'arrêt de secours nommé reste nécessaire.

Les recréations coupent streams et jobs en cours ; conserver les téléchargements et avertir
l'opérateur avant arrêt. Caddy sur réseau Docker garde ses destinations DNS après recréation ;
vérifier la santé et l'accès interne avant toute réouverture. Aucun arrêt Docker global nécessaire.

## Indispensable et différable

Indispensable : confinement des accès directs et HTTPS vulnérable, dev arrêté, debug désactivé,
protection des logs, image Auth sûre, session réelle représentative, contrôles cibles et repli sûr.
Peuvent attendre : rotation de clés non justifiée par incident, refonte du transport streaming,
SIEM/export distant, enrichissement des événements métier, séparation complète d'un futur dev
tant que l'actuel reste arrêté, centralisation et rétention longue des journaux. La borne locale
de taille des access logs fait désormais partie de A.
Le port provider 4416 est conservé et n'est pas annoncé comme fermé ; examiner sa publication
dans une intervention distincte, sans élargir silencieusement ce lot API à ce service.
La rotation/révocation immédiate des JWT n'est pas fournie par le lot 1 : caches amont et locaux
restent ceux de la procédure canonique ; ne pas présenter un redémarrage comme une purge amont.

## Autorisations à obtenir

1. **A — maintenance et confinement** : transfert des fichiers nommés, tag local de l'image
   actuelle, règles temporaires IPv4/IPv6 sur 8000/8001, écriture du Caddyfile et reload,
   arrêt dev, recréations ciblées prod et Caddy avec debug/logs/ports/rétention modifiés ; coupure en ligne explicitement acceptée.
   Nommer aussi l'arrêt de secours Caddy si le reload de maintenance échoue ; sinon ce repli n'est pas autorisé.
2. **A — retrait des règles temporaires** : seulement après contrôle des publications supprimées.
3. **Tests réels** : projet et compte dédiés désignés, appels Auth autorisés ; création éventuelle
   d'un compte prod vide exige sa propre décision, sans lecture de bibliothèque réelle.
4. **B — déploiement sous maintenance** : transfert/import de l'image vérifiée et recréation prod.
5. **B — réouverture HTTPS** : reload vers release seulement après les preuves cibles.
6. **Reprise recovery/backfill** : autorisation des effets persistants et nouvelle configuration.

Aucune de ces autorisations n'est présumée acquise par une autorisation d'audit en lecture seule.

## Points d'arrêt et décision finale de robustesse

Lors de la revue SEC-001.4, la consigne autorisait conditionnellement A si le verdict était GO.
Elle n'autorisait pas B. À cette revue du 2026-10-17, l'application immédiate restait **NO-GO** :
la durée de maintenance en ligne jusqu'à B n'est pas bornée (session réelle non disponible),
et la présence d'une session interactive de secours/console fournisseur n'est pas confirmée.
Ce sont des conditions opérationnelles, pas des défauts supplémentaires du correctif Auth.
Deux choix permettent de lever le premier point : accepter explicitement la maintenance
prolongée jusqu'à une B autorisée séparément, ou préparer la session et une fenêtre A+B avant A.
Une session réelle reste indispensable à la réouverture B, pas au filtrage des ports de A.

| Étape | Critère exigé avant de continuer | Échec / point d'arrêt |
|---|---|---|
| Avant A | Fenêtre et impact acceptés, secours SSH ouvert, nouveau login indépendant, images inchangées, sauvegardes vérifiées et espace libre | Ne rien modifier |
| Filtrage temporaire | Six règles présentes, nouvel accès SSH ; depuis le poste extérieur, TCP 8000/8001 sans réponse HTTP en IPv4 **et IPv6** | Conserver le journal/protections partielles, inventorier ; ne pas poursuivre une recréation ; ne pas annoncer confinement complet |
| Maintenance Caddy | Inode/hash hôte et conteneur concordants, validate/reload réussis ; extérieur HTTPS TLS valide : prod /health 200, /test et chemin synthétique 503, dev /health 503 ; nouveau SSH | Ne pas recréer prod ; arrêt de secours Caddy seulement dans le périmètre accepté, sinon signaler HTTPS vulnérable |
| Arrêt dev | Labels du bon conteneur, Running=false **et** Status=exited vérifiés | STOP, ne pas considérer le profil comme un arrêt |
| Recréation prod | Santé dans les 120 s, APP_DEBUG=false, commande sûre, aucune publication effective ; accès interne Caddy→prod, nouveau SSH | Garder maintenance, dev arrêté et règles ; ne pas restaurer les publications ni les bypass publics |
| Recréation Caddy / rétention | Même image, mêmes volumes, santé/routes HTTPS maintenance rétablies dans les 120 s ; LogConfig des deux services = json-file/10m/3 ; nouveau SSH | STOP sous maintenance, diagnostic ciblé sans logs bruts ; ne pas passer à B |
| Retrait temporaire | Prod sans publication, dev toujours exited ; retrait puis inventaire absent confirmé ; répéter les quatre sondes directes extérieures IPv4/IPv6 et nouveau SSH | En cas d'accès réapparu : STOP sous maintenance ; après inventaire, une nouvelle pose nominative nécessite un nouveau journal conservé avec le précédent ; diagnostiquer avant toute réouverture |

Les quatre sondes directes sont les deux ports dans chacune des deux familles. Le poste Windows
a atteint /health en IPv6 avec code 200 avant intervention : son absence de réseau IPv6 ne sera
donc pas utilisable comme justification d'un contrôle manquant. Forcer `curl.exe --noproxy "*"`
et l'adresse publique de chaque famille, sans query ni token. Une erreur DNS ou locale ne prouve
pas un blocage réseau. Les nouvelles connexions SSH doivent être indépendantes, sans réutiliser
un ControlMaster. Ne pas poursuivre automatiquement au-delà d'un point d'arrêt.

Le conteneur dev existant peut conserver des PortBindings historiques dans sa configuration
même arrêté ; ils ne sont pas actifs. Ne pas le démarrer : les futurs lancements doivent utiliser
le modèle fusionné qui retire les ports. Le candidat ne modifie pas rétroactivement sa politique
restart sans recréation. Son arrêt manuel avec `unless-stopped` est conservé ; le confirmer à
nouveau après toute opération Docker et après un éventuel redémarrage hôte hors de ce lot.

Budget indicatif non mesuré sur le VPS : 5–15 minutes pour A et ses contrôles, dont 1–3 minutes
de recréation/contrôle prod ; interruption HTTPS complète possible de quelques secondes à
environ 30 secondes lors de la recréation Caddy. Les délais de 120 s ci-dessus sont des seuils
d'arrêt, pas des garanties de disponibilité. Les fonctions en ligne seront en 503 **pendant toute
la période entre A et la réouverture B**, potentiellement bien au-delà de cette fenêtre.

### Vérifications supplémentaires réellement exécutées

- Relecture distante sans écriture : mêmes trois images, logs json-file sans limites,
  Caddy bind ordinaire non symlink en lecture seule (device 2049, inode 1310859), SSH 22,
  environ 115 Go libres. Deux connexions SSH indépendantes réussies ; IPv6 extérieure /health 200.
- Python 3.12/Linux dans l'image locale amd64, réseau désactivé : cinq tests passent, incluant
  les douze sous-cas d'échec d'insertion (six positions, avant/après effet noyau simulé), retrait
  partiel avec IPv6 indisponible puis reprise, préflight défaillant, double apply refusé,
  sauvegarde/restauration en place visible depuis un descripteur déjà ouvert, inode inchangé.
  Le pare-feu est simulé : aucune règle réelle n'a été installée, même localement. Le test de
  fichier n'est pas un essai de restauration du bind mount sur le VPS ; hashes/inodes montés
  et configuration active devront être confirmés aux points d'arrêt.
- Projet Compose local isolé, sans port/volume AURA : démarrage du seul service fixture sous
  profil, `compose stop dev` sans profil explicite, puis Running=false confirmés sur Compose
  5.5.1. La commande cible précise aussi `--profile disabled-dev` pour Compose 5.1.2 ; aucun
  arrêt réel dev n'est prétendu avant A.
- Image B inchangée exportée localement, plateforme linux/amd64, archive gzip de 291 131 353
  octets. SHA-256 : `b2efc78bd0e7e64731a3aa4c09691b7f0ee0d21e4a5832d80b119c6cc5a2d872`.
  ID image : `sha256:845680a8d0e8cba0ea37f52eb1254211ee9e9b4f5c4c89e547723a02a4065739`.
  Artefact conservé dans la collection locale Codex Security, `artifacts/SEC-001.3/lot1-prepared-20261017-amd64.tar.gz`.
  Les 21 blobs de l'archive ont été vérifiés cryptographiquement, ainsi que la chaîne index,
  manifeste de plateforme et configuration. Manifeste linux/amd64 :
  `sha256:55b614cd9839de05b6b3eabb5a1b5f7a52fde309be59a554b12bdadb4e984049` ; configuration :
  `sha256:7d7f18f5c403da6550195bb3ef6da86f21d6c8e2bc6db01a26b4c139bf72d22e`.
  Avant B : vérifier le hash après transfert autorisé, charger sous maintenance puis comparer
  l'identité et les empreintes applicatives ; ne pas confondre hash de l'archive et ID Docker.
  Le moteur local expose l'index OCI comme `.Id` ; un stockage Docker classique peut exposer
  la configuration. Consigner la représentation cible et vérifier qu'elle correspond exactement
  à l'index, au manifeste amd64 ou à la configuration ci-dessus, avec plateforme et hashes
  Auth/routeur/logging conformes au manifeste source. Tout autre ID est un STOP, aucun retag
  pour masquer un écart. Le premier contrôle local assimilant `.Id` au hash de configuration
  a échoué ; la vérification complète OCI a ensuite réussi, sans modifier l'image.
  Prévoir au moins 1 Go libre
  pour archive, import et marge, sans supprimer les volumes ni l'image de secours.

Les 149 tests backend précédents restent la preuve applicative ; ils ne sont pas réexécutés pour
ces seuls changements de runbook, pare-feu candidat et options Docker. La session Supabase,
le trafic candidat cible, le secours console et l'arrêt réel dev restent non exécutés/non confirmés.

## Fenêtre A+B coordonnée — SEC-001.5

### Préconditions hors maintenance

- Le compte Auth dédié est créé sur autorisation explicite, sans email envoyé ni bibliothèque.
  Aucun trigger utilisateur sur `auth.users` trouvé avant création. Stockage local des credentials
  sous DPAPI CurrentUser, ACL utilisateur/SYSTEM et exclusion Git vérifiés ; aucun JWT/refresh
  token n'est enregistré dans ce fichier. La clé privilégiée est restée dans le backend distant.
- Connexion réelle et refresh réussis, **ES256 vérifié cryptographiquement** par le code du lot 1
  dans l'image préparée. Route privée éphémère 200, UUID/manquant/signature altérée/header
  invalide prioritaire 401, vrai token en query brut/préfixé : WAV 200 et Range 206, octets comparés.
  Tous ces contrôles sont locaux avec le projet réel et un WAV temporaire, sans accès au backend
  vulnérable, à ses bibliothèques ou à sa base. Ils ne remplacent pas les sondes cibles.
- Le premier token réel expire environ une heure après émission. La sonde `--wait-expiry`
  l'a gardé uniquement en mémoire hors maintenance. Le rapport final est réussi : **expiration
  réelle 401, logout local 204**, login et refresh ES256 vérifiés, streaming isolé 200/206.
  Le processus est terminé sans token persisté. Aucun TTL, horloge ou signature n'a été modifié.
- L'opérateur signale ECC P-256 et HS256, avec « Last rotated at » il y a neuf mois. Les tokens
  frais/refresh prouvent ES256 actif. La continuité attendue des sessions usuelles repose sur
  leur renouvellement ES256 ; aucun ancien token HS256 encore valide n'a été testé. Ne pas
  ajouter de secret HS256, révoquer une clé héritée ni toucher aux clés API pour cette fenêtre.
  La date est une confirmation opérateur, pas une donnée lue par l'agent dans le Dashboard.
- Deux connexions SSH indépendantes réussies ; port 22. NTP cible synchronisé et écart d'horloge
  estimé inférieur à dix secondes. Au démarrage, garder un canal SSH de secours ouvert et
  contrôler une nouvelle connexion à chaque gate. Ne pas confondre ces essais avec une
  garantie de disponibilité future du réseau.
- Contabo : l'opérateur confirme **VNC enabled**, mais signale une connexion non chiffrée.
  VNC offre l'accès à la console lorsque SSH/réseau ne fonctionne plus ; il reste facultatif
  dans ce périmètre qui ne vise ni SSH 22 ni les politiques générales du pare-feu. Ne pas exiger
  de connexion VNC non chiffrée ni y saisir un mot de passe root. Aucun changement VNC, de mot
  de passe ou reboot n'est demandé/exécuté. La connexion VNC/Linux n'est pas validée et ne bloque
  plus A+B ; les deux canaux SSH et les contrôles après chaque étape restent indispensables.
  Source : [guide Contabo](https://help.contabo.com/en/support/solutions/articles/103000407800-how-to-connect-to-your-server-using-vnc).
- Les répertoires sync effectifs prod/dev sont `/app/downloads/sync_files`, non vides et couverts
  par le volume existant ; seuls le chemin et des booléens ont été lus, pas les fichiers.
- Image B et archive déjà identifiées ci-dessous ; hash de l'archive conservée revérifié.
  Le paquet opératoire séparé contient exclusivement les candidats `infra/security-lot1/`
  et les deux fichiers `server/app/core/operational_logging.py` / `security-logging.json`.
  Son manifeste liste chaque fichier et son hash ; pas de `.env`, credentials, cache Python,
  journal brut, volume ni remplacement de `infra/caddy/Caddyfile` par extraction automatique.
  Les sondes sont montées/transmises séparément, sans reconstruire l'image applicative.

**GO de début de fenêtre uniquement si** fin du test d'expiration réelle, deux canaux SSH vérifiés
dont le secours conservé ouvert, prépositionnement vérifié, baseline sans dérive et autorisation A+B acquise. Sinon,
rester en préparation ; l'acceptation de 30 minutes ne vaut pas ordre de démarrage.

Artefacts gelés localement pour cette préparation :

- `security-lot1-ops-20261017.tar.gz` : 19 fichiers, 17 341 octets,
  SHA-256 `9c263b285a726921dea0fd10189d1b07ecaa0c934686ad50f42a4516a4162d66`.
- `security-lot1-ops-manifest.json` : SHA-256
  `ab0bb4f15576066e5975d262c03b8f6aca952ef02b79afcbb7dcc102c160e6e1`, fichiers/hashes,
  commit de référence et identités OCI de B. L'exemple `.env.example` ne contient que les
  sélecteurs d'images publics ; aucun `.env` réel ni fichier chiffré de credentials n'est empaqueté.
- Image B : archive `lot1-prepared-20261017-amd64.tar.gz`, identité et SHA-256 détaillés ci-dessous,
  inchangés. Aucune reconstruction ; transfert et import P0 réalisés comme décrit ci-dessous,
  sans déploiement de cette image.
- Quatorze tests Linux des nouvelles sondes passent : cinq session/crypto/HTTP simulé,
  trois fixture/nettoyage cible, trois présence/rédaction des logs, trois garde image/reçu
  d'intervention. Le premier test d'expiration simulé avait une durée trop courte (1 s) pour
  les vérifications préalables ; fixture corrigée à 15 s, puis essais concluants. DPAPI et ACL
  ont aussi été contrôlés sur Windows. Les 149 tests backend antérieurs ne sont pas relancés
  pour ces outils opérateurs ; aucun code applicatif supplémentaire n'a été modifié.

Les deux fichiers opératoires et l'archive B sont conservés dans la collection locale Codex
Security, sous `artifacts/SEC-001.5/` et `artifacts/SEC-001.3/` respectivement. Toute modification
ultérieure d'un candidat exige de régénérer le paquet/manifeste avant autorisation d'exécution.

### Ordre final et budget

| Période indicative | Actions et preuve pour continuer |
|---|---|
| P0, avant T0, sans coupure | Après autorisation P0 : transférer paquet + archive B vers staging root protégé, vérifier tous les hashes, importer l'image et vérifier identité/plateforme/sources. Aucun service lancé, arrêté ou rechargé ; aucune règle/configuration active modifiée. Image et candidats seulement prépositionnés. |
| T0–10 min, A | Refaire baseline et backup Caddy inode/hash, ouvrir le secours SSH ; poser les six règles et sonder IPv4/IPv6 ; maintenance Caddy validée/rechargée ; dev arrêté et état confirmé ; recréer prod avec ancien runtime confiné + logging sûr/debug=false/ports retirés, puis Caddy avec rétention. Gates de la revue SEC-001.4 obligatoires. |
| T10–20 min, B sous maintenance | Recréer uniquement prod avec l'image B vérifiée, routines recovery/backfill toujours false ; santé, source/CMD/debug, /test 404 et refus 401. Exécuter le contrôle de logs synthétique avant tout vrai token sur la cible, puis la sonde réelle interne et vérifier son nettoyage. |
| T20–25 min, réouverture conditionnelle | Seulement si tous les contrôles internes passent : valider/installer/recharger Caddy release ; exécuter immédiatement les sondes HTTPS extérieures et la vraie session/audio 200/206 via Caddy. En cas d'échec, réinstaller la maintenance filtrée en place, sans ancienne image publique. Dev doit rester 503 et les quatre accès directs fermés. |
| T25–30 min | Confirmer logs filtrés/rétention, dev exited, nouvel SSH, santé HTTPS et nettoyage des seuls fichiers de test ; retirer les règles temporaires seulement après dépulication effective, puis recontrôler IPv4/IPv6. Consigner résultats et durée réelle. |

À T25 si la validation n'est pas acquise, ou dès un contrôle bloquant : rester/revenir en
maintenance sûre, signaler l'échec et le dépassement possible, attendre la décision opérateur.
À T30, aucun retour automatique au code vulnérable, aucune nouvelle réparation ou extension
du périmètre sans décision. Le maintien des protections/maintenance ne vaut pas autorisation
de poursuivre des changements. Les budgets sont indicatifs, pas un engagement de remise en ligne.
Recovery/backfill, dev, mises à jour de secrets, rotation de clés, migrations, versions et push
restent hors autorisation A+B. Aucun arrêt Docker/hôte global ni suppression de volume/image.

### Sondes prêtes, à exécuter seulement à leur gate

```powershell
# Déjà lancé pour le compte dédié ; ne pas lancer une deuxième attente en parallèle.
python -B infra/security-lot1/windows-session.py probe --wait-expiry
# Après B et contrôle synthétique des logs, autorisation des deux fichiers de test requise :
python -B infra/security-lot1/windows-session.py target-check internal
# Après réouverture Caddy conditionnelle, même mécanisme via HTTPS :
python -B infra/security-lot1/windows-session.py target-check public
```

Le helper confirme l'image/CMD et les quatre empreintes source Auth/routeur/logging avant
d'envoyer un vrai token au backend corrigé. Chaque sonde cible crée exclusivement un WAV et
son JSON avec noms aléatoires sous le hash du seul UUID dédié, sans écraser de fichier existant,
puis les retire dans `finally`. Avant toute création, l'hôte garde un reçu 0600 dans le dossier
root 0700 `/root/aura-sec0015-fixtures/` : nonce, deux chemins hashés exacts, absence préalable,
puis résultat de nettoyage, sans UUID brut, mot de passe ou token. Un reçu est créé par sonde
interne/HTTPS ; chacun utilise un WAV+JSON distincts. Le dossier audio n'est retiré que s'il a
été créé et reste vide ; pas de glob ni suppression récursive. En cas d'interruption brutale,
considérer le nettoyage non confirmé et contrôler uniquement les chemins du reçu avant un
retrait autorisé, sans lister les bibliothèques. Ne jamais utiliser upload/provider pour ce test.

Après B, sur le VPS, le contrôle sans vraie session :

```bash
python3 -B infra/security-lot1/target-log-check.py
docker inspect --format '{{json .HostConfig.LogConfig}}' aura-api aura-caddy
```

Le contrôle de logs n'emploie que des marqueurs et conserve les journaux sur le VPS, retourne
des booléens et exige une trace structurée sans query. Il ne simule pas les erreurs des binaires
providers ni des collecteurs externes. Les erreurs Python/proxy restent couvertes par les essais
isolés déjà consignés et par l'identité des configurations ; ne pas annoncer un essai d'erreur
réel cible qui n'aurait pas été exécuté.

### Autorisations — P0 exécutée ; A+B accordée, reprise autorisée puis terminée

1. **P0 :** « J'autorise le prépositionnement sur le VPS AURA du paquet opératoire et de l'archive
   image B identifiés par leurs manifestes/hashes SEC-001.5, leur stockage root protégé et l'import
   de cette image uniquement. Aucun arrêt, reload, pare-feu, changement actif, secret ou migration. »
2. **A+B :** « J'autorise la fenêtre A+B SEC-001.5, objectif 30 minutes, une fois les préconditions
   vérifiées : sauvegardes et installation des fichiers nommés, règles temporaires IPv4/IPv6 puis
   retrait contrôlé, maintenance Caddy et arrêt de secours Caddy si nécessaire, arrêt dev,
   recréations ciblées prod/Caddy, image B vérifiée, debug/logs/rétention/ports corrigés, appels
   Auth du seul compte dédié, un WAV+JSON synthétiques par sonde interne/HTTPS dans son espace,
   reçus root protégés sans credentials et nettoyage
   précis. La réouverture du backend corrigé est autorisée seulement après succès interne, avec
   vérification extérieure immédiate et retour en maintenance sur échec. Aucun retour public à
   l'ancien backend. Si validation impossible ou délai dépassé : maintenance sûre et attente de
   ma décision, sans autre modification. »

La suppression future du compte Auth de test n'est pas incluse ; le garder dédié, protégé et sans
bibliothèque jusqu'à décision de nettoyage. Ces autorisations peuvent être données ensemble,
mais ni la création du compte déjà approuvée ni l'acceptation de 30 minutes ne les remplacent.

### Résultat P0 et préconditions finales

L'opérateur a autorisé **P0 SEC-001.5 uniquement**, sans changement actif, puis confirmé une
maintenance de 30 minutes pouvant être prolongée plutôt que réexposer l'ancien backend.
Cela ne vaut pas autorisation A+B. Le staging root 0700 est
`/root/aura-sec0015-p0-7vockd8b`, fichiers et reçus 0600, candidats sous `payload/` uniquement.
Ni les Compose actifs ni `/opt/aura/infra/caddy/Caddyfile` n'ont été remplacés.

Empreintes comparées localement puis sur le VPS :

| Artefact | SHA-256 |
|---|---|
| `lot1-prepared-20261017-amd64.tar.gz` | `b2efc78bd0e7e64731a3aa4c09691b7f0ee0d21e4a5832d80b119c6cc5a2d872` |
| `security-lot1-ops-20261017.tar.gz` | `9c263b285a726921dea0fd10189d1b07ecaa0c934686ad50f42a4516a4162d66` |
| `security-lot1-ops-manifest.json` | `ab0bb4f15576066e5975d262c03b8f6aca952ef02b79afcbb7dcc102c160e6e1` |

Les 19 membres du paquet et 21 blobs OCI sont vérifiés. L'image importée est
`aura-security-lot1:prepared-20261017-amd64`, ID
`sha256:845680a8d0e8cba0ea37f52eb1254211ee9e9b4f5c4c89e547723a02a4065739`,
plateforme linux/amd64 ; couches rootfs, CMD, environnement et répertoire de travail concordent
avec la configuration de l'archive. Aucun conteneur de cette image n'a été créé ou démarré.

Les cinq conteneurs existants conservent leur état, leurs identités/configurations, leurs dates
de démarrage et compteurs de restart. Caddyfile : inode 1310859/device 2049 et SHA-256
`149ddb05c207cabc0861df0ef5a81b59b6d0c53565cee6fafc19cdbe6078859a` inchangés ;
configuration Caddy chargée : SHA-256
`4f22a63e03a643a735f8b416481cb0f29f3a6fa564d18214a34c4ddd74a94988` inchangé.
Règles filter/NAT IPv4/IPv6 et UFW inchangés. Un premier contrôle a confondu l'ordre variable
de `Mounts` renvoyé par Docker avec un changement ; l'empreinte originale a été retrouvée par
les seules permutations des montages, sans modifier la référence. L'image n'a été chargée
qu'une fois ; la finalisation a inspecté l'image déjà importée.

Précontrôles en lecture seule : Compose 5.1.2 cible fusionné confinement/release conforme,
debug/routines false dans le modèle candidat, ports retirés, dev sous profil, Caddy dépendant
de prod seulement, rétention et volumes attendus. Les deux Caddyfiles ont été **adaptés** sur
Caddy cible, sans provisioning/validate/reload ; leurs validations avant bascule restent des
gates A+B. Outils requis et chaînes INPUT/DOCKER-USER des deux familles disponibles. Un premier
contrôle final partagé avait une collision de nom Python entre helpers ; le précontrôle était
déjà réussi et a été confirmé dans un contexte indépendant, sans refaire les opérations.

Deux SSH indépendants sont validés sans multiplexage ; le canal de secours est gardé ouvert
et a encore répondu après P0. Recontrôler sa présence et un nouveau login juste avant A.
Backend prod/dev encore healthy ; HTTPS prod/dev et santé directe IPv4 8000/8001 répondent 200.
Cela confirme le maintien du service et ne représente pas une activation des protections.

**Réserve IPv6 mesurée :** depuis le poste, les ports 8000/8001 et 443 du VPS expirent (curl 28),
alors qu'un autre serveur HTTPS IPv6 répond 200. Le VPS a une adresse publique et une route
source eth0, mais son handshake TLS sortant IPv6 a aussi échoué. Il écoute encore en IPv6 sur
443/8000/8001 ; aucune règle locale n'a changé. Les domaines AURA ne publient pas d'AAAA.
La cause de cette connectivité IPv6 n'est pas établie et aucune correction réseau n'a été faite.

Cette réserve ne constitue ni une preuve de filtrage ni une validation de disponibilité IPv6.
Elle n'empêche pas de proposer le confinement et le déploiement sur le service HTTPS actuellement
IPv4, à condition de la signaler à l'opérateur. Les gates IPv6 demeurent obligatoires : six règles
inventoriées, absence effective de publications et d'écoute hôte 8000/8001 après A, et sondes
extérieures consignées sans attribuer leurs timeouts au seul pare-feu. Ne pas modifier la route,
le DNS, UFW ou SSH pour corriger l'IPv6 dans A+B sans nouvelle autorisation spécifique.

À l'issue de P0, les préconditions Auth, SSH et artefacts étaient remplies et les points d'arrêt prêts ;
les effets des futures mutations n'étaient pas déclarés testés. L'opérateur a ensuite autorisé A+B,
avec cette réserve IPv6 connue. En cas d'échec, maintenir le confinement et demander la décision,
même si la maintenance dépasse 30 minutes ; ne jamais revenir au backend vulnérable public.

### SEC-001.6 — historique : A commencée, arrêt avant recréations

- Sauvegarde root 0700 : `/root/aura-sec0015-p0-7vockd8b/window-ab/`, fichiers/reçus 0600.
  Original Caddy conservé uniquement pour diagnostic, candidat maintenance sauvegardé ;
  19 fichiers installés sans écraser de candidat existant, aucun `.env` copié/modifié.
- Baseline P0 inchangée avant intervention ; B et Caddy identifiés, environ 114 Go disponibles.
  Deux SSH indépendants, secours encore ouvert après l'arrêt.
- `ports-temporary.sh apply .../window-ab/ports.json` réussi : six règles présentes ;
  inventaire après arrêt toujours conforme. Sondes Windows 8000/8001 IPv4 sans HTTP,
  timeouts IPv6 consignés avec leur réserve préexistante. Aucun retrait ni flush.
- La commande de bascule maintenance retourne `AssertionError` dans le helper opérateur,
  puis son repli nommé `docker stop aura-caddy` réussit. Le helper ne conservait pas l'emplacement
  de l'assertion : ce défaut de diagnostic doit être corrigé localement avant une reprise ;
  ne pas rejouer la bascule simplement pour obtenir une traceback sur la cible.
- Diagnostic en lecture seule : inode `[2049, 1310859]` conservé, hash hôte et bind Caddy
  `a6168d7eb49e6dd6d6d3f130014eb9b6dbcf1207e9a5363dc61786040b65015b`, candidat exact.
  L'autosave lu après arrêt diffère du candidat maintenance adapté localement, sans routes
  statiques maintenance ; il ne constitue pas une preuve du contenu actif au moment de l'échec.
  La cause de l'assertion/du non-confirmé reste ouverte, aucun reload réussi n'est revendiqué.
- Contrôle extérieur après arrêt : HTTPS prod/dev connexion refusée (curl 7), directs IPv4
  et IPv6 sans HTTP (curl 28). SSH neuf et secours répondent. L'ancien runtime demeure interne,
  avec debug/logs et ports historiques configurés ; les règles assurent le confinement provisoire.
  Ne pas annoncer dépublication durable, arrêt dev, rétention ou correction Auth effective.
- Aucune vraie session envoyée à l'ancien runtime et aucune fixture distante créée.
  Aucune recréation prod/Caddy, démarrage/restart, migration, push ou version modifié.

La reprise demandée après revue consistait à corriger/valider localement les diagnostics du helper ;
refaire les contrôles SSH/règles/sauvegarde, diagnostiquer Caddy sans restaurer son original,
établir et prouver la maintenance filtrée avant tout redémarrage public, puis reprendre les gates
arrêt dev, prod A, rétention Caddy, B, logs synthétiques, session cible et réouverture conditionnelle.
Un nouvel échec impose de nouveau arrêt de la progression et décision opérateur.

### SEC-001.6 — reprise autorisée et résultat final

L'opérateur a répondu « oui » à la demande de diagnostic correctif puis reprise sous maintenance.
Le helper opérateur v2 fournit fonctions/lignes de traceback et étape de commande, sans valeurs
d'exception ni sorties brutes ; égalité/rejet de différence de configuration testés localement.
L'emplacement de l'assertion initiale reste inconnu : ne pas prétendre l'avoir reproduite ou
attribuée à Caddy. Le démarrage sous maintenance et le reload release ont ensuite réussi avec
comparaison exacte JSON actif/adapté ; aucune règle de validation n'a été assouplie.

| Gate réellement exécuté | Heure Europe/Paris et résultat |
|---|---|
| Reprise lecture seule | 19:41:15 : sauvegardes, 19 candidats, images, six règles, commande Caddy sans `--resume`, SSH vérifiés |
| Maintenance rétablie | 19:41:30 : candidat validé en conteneur éphémère sans réseau/ports, Caddy démarré sur le fichier maintenance ; JSON actif exact, inode/bind conservés ; extérieur santé200, /test503, dev503 |
| Arrêt dev | 19:41:55 : labels exacts, Running=false et Status=exited |
| Prod A | 19:42:13 : ancienne image confinée sans ports, santé, debug/routines false, commande de logging sûre et json-file10m/3 |
| Caddy A | 19:42:41 : même image et mêmes volumes de certificats, rétention10m/3 ; maintenance extérieure et nouvel SSH confirmés |
| Prod B | 19:43:19 : image `sha256:845680a8d0e8cba0ea37f52eb1254211ee9e9b4f5c4c89e547723a02a4065739`, quatre sources conformes, santé200, /test404 et refus401 |
| Logs avant vraie session | 19:43:34 : marqueurs query/Authorization/Referer absents des logs API/Caddy, traces structurées conservées ; journaux restés sur place |
| Session réelle interne | Avant réouverture : audio200/Range206 avec octets comparés, refus401, /test404 ; WAV+JSON temporaires créés puis supprimés |
| Réouverture | 19:44:42 : candidat release validé/rechargé, inode conservé, hash hôte/bind `beb9c32353c174270adec49fb6840a437ad353e26de5a366598c99a194bc4586`, JSON actif exact ; extérieur santé200, /test404, privé401, dev503 |
| Session rafraîchie via HTTPS | Après nouveau contrôle synthétique des logs à 19:45:27 : connexion200, refresh200 puis session rafraîchie acceptée via Caddy, audio200/206, refus401, /test404 et logout réussi ; second WAV+JSON supprimés |
| Retrait nominatif | 19:45:50 : absence de publications et d'écoute hôte vérifiée, six règles du seul journal retirées puis inventoriées absentes |
| Contrôles finaux | 19:46:04–06 : healthy, Caddy release actif, dev exited/503, rétention deux conteneurs, deux reçus de nettoyage et quatre chemins exacts absents ; aucune écoute8000/8001 ; quatre sondes extérieures sans HTTP et nouveau SSH ; secours encore répondant |

La sonde HTTPS réelle s'exécute depuis le conteneur VPS via le domaine public/Caddy avec TLS ;
les contrôles HTTPS sans session et des ports directs s'exécutent depuis Windows. Ne pas
présenter la sonde authentifiée comme provenant du poste extérieur. L'expiration réelle 401
a été vérifiée avant la fenêtre dans la même image locale, pas en attendant une nouvelle heure
sur le VPS. Logout valide l'appel API ; refus d'un refresh après logout/révocation immédiate
de l'access token encore valide non testés et non promis. Anciennes sessions HS256 toujours
non testées réellement, aucune clé/secret changé.

Reste volontairement inchangé : routines recovery/backfill désactivées, dev partagé arrêté,
contrat de streaming et versions clients, migrations, secrets et port provider4416 hors périmètre.
La cause de la connectivité IPv6 préexistante n'est pas résolue. L'absence d'écoute hôte et de
publication apporte la protection durable ; les timeouts IPv6 ne sont pas attribués au pare-feu.

### Commande obligatoire pour les recréations futures

Les deux Compose de base et le checkout applicatif distant demeurent antérieurs au lot 1.
Ne pas reconstruire depuis ce checkout ni lancer `up` avec les seuls fichiers de base : cela
pourrait rétablir l'ancienne image, les ports/debug ou dev. Conserver les quatre fichiers
ci-dessous et vérifier l'identité immuable de B ; l'arrêt/recréation future exige sa propre
autorisation. Les conteneurs actuels redémarrent avec leur configuration déjà corrigée.

```bash
cd /opt/aura
export AURA_SECURITY_IMAGE=aura-security-lot1:prepared-20261017-amd64
docker compose -p infra \
  -f infra/docker-compose.vps.yml \
  -f infra/docker-compose.vps.caddy.yml \
  -f infra/security-lot1/compose.containment.yml \
  -f infra/security-lot1/compose.release.yml config --quiet
# Pour une recréation ultérieure autorisée : mêmes quatre -f, aucun profil dev,
# up -d --no-deps --no-build --pull never --force-recreate aura-api
```

Ne pas retirer les overrides ni relancer dev/recovery/backfill sans décision séparée.
Les sauvegardes d'avant A ne sont jamais un repli public autorisé.

## Préparation vérifiée le 2026-10-17

Image locale préparée : `aura-security-lot1:prepared-20261017-amd64`, ID immuable
`sha256:845680a8d0e8cba0ea37f52eb1254211ee9e9b4f5c4c89e547723a02a4065739`.
Manifeste des fichiers du contexte :
`707136b3ff09713583dfc541b6fa777afa44f0ad2820e72d874aeae23e52eaca`.
Elle dérive hors réseau de l'image Python 3.12 du Dockerfile de production déjà revue ;
les dépendances restent Python 3.12.15, PyJWT 2.15.1 et Uvicorn 0.54.0. Tous les fichiers
applicatifs copiés ont été comparés au manifeste dans l'image et le runtime de tests.
L'exemple de configuration est inclus ; aucun `.env`, cookie ou credential réel n'est ajouté.
Lors de cette préparation initiale, l'image n'avait pas été transférée. P0 l'a désormais
transférée et importée ; elle n'a été ni poussée vers un registre ni déployée.

Les 149 tests backend passent (un warning de dépréciation Starlette/httpx, aucun skip/xfail).
La sonde HTTP isolée vérifie santé, route privée, refus invalides/expirés, priorité du header,
streaming 200 et Range 206 avec octets attendus, /test 404, quatre erreurs synthétiques sans
marqueur/token en sortie et avec identifiant de requête. Le lanceur démarre aussi avec les
sources Auth/routeur d'avant lot 1 chargées en mémoire, pour la transition A sous maintenance.

Les candidats Caddy complets sont validés et testés sur Caddy 2.8.4 ARM64 local : 96 requêtes
à marqueurs, statuts attendus vérifiés par route/host, chemins/identifiants/erreurs préservés
sans marqueur. L'adaptation des deux configurations par Caddy 2.8.4 x86_64 du VPS et la fusion
Compose 5.1.2 Linux ont réussi en mémoire, sans copie/reload/provisioning. Cela ne remplace pas
les essais de trafic et TLS du candidat sur la cible. La première préparation du script réseau
ne couvrait que `sh -n` ; la revue de robustesse ci-dessus ajoute les essais d'échecs simulés.
Aucune règle n'a été installée et l'effet IPv4/IPv6 devra être contrôlé après le GO.

La revue indépendante n'a identifié aucun bypass concret ou régression ASGI/audio/Auth dans
le périmètre candidat. Une affirmation initiale de fermeture de 4416 a été corrigée dans ce plan.
Les sessions réelles, signatures actives/anciennes, canaux natifs/collecteurs, trafic du candidat
sur cible et artefact de repli public sûr ne sont pas validés. Le plan est préparé pour décision ;
le déploiement et la réouverture ne sont pas déclarés prêts.

## Références techniques

- [Docker et UFW](https://docs.docker.com/engine/network/packet-filtering-firewalls/#docker-and-ufw).
- [Fusion Compose et reset](https://docs.docker.com/reference/compose-file/merge/).
- [Publication des ports](https://docs.docker.com/engine/network/port-publishing/).
- [Filtrage Caddy 2.8.4](https://github.com/caddyserver/caddy/blob/v2.8.4/modules/logging/filterencoder.go).
