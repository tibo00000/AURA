# SEC-002.4 — Candidat et exécution du lot 2

## Décision et périmètre

**Lot 2 déployé et service rouvert le2026-10-18.** Migration autorisée appliquée, image figée
active et contrôles M5–M8 réussis. Maintenance publique de20:34:03 à20:41:22UTC, soit7min19s.
Le jeu M2-S vérifié est conservé, sans nouvel export. Voir le bilan d'exécution en fin de document.
Le forfait ne fournit pas de backup géré exploitable selon l'opérateur ; aucun Pro/PITR activé.
Les commandes ci-dessous restent une procédure ; ce document n'autorise pas leur réexécution.

Le gate M2 est précisé par [SEC-002.5 — récupération](security-lot2-recovery.md) : ce complément
prévaut sur l'exemple public/auth et le traitement du second dump ci-dessous. La copie de
livraison SEC-002.4 reste figée ; l'utiliser avec ce complément, sans régénérer le candidat.
La précision M3 ci-dessous prévaut également sur la section M3 de cette copie historique.
Décision initiale après M2 : GO conditionnel pour préparer M3 uniquement. L'opérateur a ensuite
demandé d'appliquer la maintenance et les étapes nécessaires, puis explicitement autorisé
la migration nominative via le wrapper. Ces autorisations et leur exécution sont consignées ci-dessous.

Autorisation du préflight SEC-002.4 initial : **lecture seule**, inventaires I1/I2, I3 conditionnel,
métadonnées de sauvegarde si accessibles, sondes anonymes HTTPS/Data API et audit SSH des
protections/horloge. Aucun fichier distant écrit, aucune donnée privée ou journal brut extrait.
Les tests avec écritures sont restés dans `nknbuhccnvvhrghvhmll`, déjà autorisé en SEC-002.3.
Ni transfert VPS, ni push, migration/configuration production, restart, dev/recovery/backfill.

## Identité figée et chaîne des sources

| Élément | Identité / SHA-256 |
|---|---|
| Base Git lot1 | `16332183c7f453c27ce840658f862b6183947208`, parent `c038d4b3752d56265721e23f21b1935876928324` |
| Patch SEC-002 | `2fb6e4ab207c7bba20ca94c12a0d231d074a7295abf07f1057a2f4e4e8916c62` |
| Patch SEC-002.1 | `0bdb3ea4dd936b611e9634635b477f76ea70408e03bcc4ef424529e0b24b433e` |
| Patch SEC-002.2 | `f7537fa9bbe99be818b086f1e26ab09d32bfa850c71a8be90e3ffffa7b284ea1` |
| Base runtime locale archivée | `sha256:5c541f103b62672bc5551aa093f840c878c7129797665ed63d2f0db5a874e16e` |
| **Image finale autonome Linux AMD64** | **`sha256:3dedda8e3518d5f12ffc65f7765d92310afa46d83b63e006bbec61fdbd619bb2`** |
| Configuration OCI | `sha256:290c3124700b08ee34baac77ed33e6b37136870852b855b7ab6b220bb210b495` |
| Archive image `security-lot2-rc-amd64.tar.gz` | `b8a7002fcb4b95f8e7725e5bed0f957df99c7dbde79798afc970b180c471e866` |
| Archive base `security-lot2-runtime-base.tar.gz` | `8607515aa0d319ede6aea50c8757a92624895a74b1cf1413e51264c0975a29b0` |
| Archive contexte `security-lot2-build-context.tar.gz` | `f84f8042b70807a6c054ff8a5fb5e41fdd1a92e1872df3ccbb3c895f1b8bb70c` |
| Manifeste contexte | `5ea8d711913fc1f52a338c98f92ad3461a1468ef5590290369f1039d03ac15fe` |
| Migration **`20261017200822_secure_user_data_isolation.sql`** | **`ffa144f458f65cd2a0ba000ee07fbbeb6faa352cf9d6316f7c9e0b40a806afa4`** |
| Wrapper `apply-sec0024-guarded.sql` | `25598403df4f650c9f1d954df78d2b1f1d7c984807b922c95320d82ee6c3aa8d` |
| Dépendances Python `dependencies.lock` | `8fb80be4906fe33ab4f2a10738948339d201615e50f780dbbb8d55f3426b518d` |

Les lots 2 restent non commités : ce sont des snapshots de travail, pas une série de commits.
Les manifestes historiques décrivent les bases exactes des trois patches : le premier était
incrémental par rapport à un arbre contenant déjà le lot1, non au HEAD propre `c038d4b3`.
La livraison SEC-002.2 avait vérifié la chaîne et l'aller/retour des patches. SEC-002.3 ajoute
outillage/fixtures/runbooks ; ses empreintes métier `me.py`, SyncService et migration sont
identiques à SEC-002.2. Le contexte figé capture ces octets, y compris les fichiers non suivis.
Le diff cumulatif livré depuis `16332183...`, son inventaire par fichier et l'archive de contexte
permettent de reconstruire l'état final sans dépendre de l'arbre de travail courant.

Artefacts locaux : `scratch/sec0024/delivery/`. `candidate-manifest.json`,
`worktree-delta-manifest.json` et `security-lot2-cumulative.patch` y donnent la liste exhaustive
des fichiers, les hashes avant/après et le périmètre. Les rapports lot1 préexistants sont exclus
du delta lot2. Aucun fichier client, version, secret ou environnement réel n'est inclus.

Contenu fonctionnel : client JWT par requête sans fallback privilégié ; routes `/me` et sync
scopées ; contrôles propriétaire/parent, collisions et idempotence par utilisateur ; RPC invoker
avec verrou parent et contrainte de position différable ; REST ajout/retrait/PATCH atomiques et
horodatage PostgreSQL monotone ; curseur jobs filtré ; RLS restrictive, ACL, PK/FK composites ;
SDK Supabase minimum2.33. Les tests A/B, diagnostics devenus régressions, inventaires, runners,
fixtures, manifests et documents sont compris dans l'inventaire. Contrats clients inchangés.

SEC-002.4 ajoute uniquement les outils de construction/vérification/archivage, le cinquième
override Compose, le wrapper d'intégrité, le postcheck, la sonde positive future et ce runbook.
L'override supprime l'ancien bind `/app/scripts` ; le code et l'outillage sont embarqués.
Le volume existant `infra_aura-downloads` reste le seul volume applicatif nécessaire.

## Reproduction et vérification locales

Construction hors réseau depuis une base locale immuable vérifiée, sans apt/pip/Deno flottant.
Le vieux paquet applicatif installé et les sources héritées visibles sont retirés ; le nouveau
code importe depuis `/app/app`. Les outils pytest et leur dépendances restent figés dans cette
RC afin de tester directement son contenu. Ils ne sont pas activés par la commande de service.

Python **3.12.15**, Supabase/PostgREST/Auth/Storage/Realtime **2.33.0**, PyJWT **2.15.1**,
Uvicorn **0.54.0**, cryptography **50.0.2**, Deno **2.9.7**, FFmpeg **7.1.5**.
Le lock contient toutes les versions Python ; la base archivée fige aussi les dépendances OS.
97 fichiers copiés sont vérifiés dans l'image, plus la recette Docker hashée séparément.
Les 23 couches ont été inspectées : zéro fichier `.env` réel/cookies/credentials et zéro
motif de clé privée, clé serveur ou JWT réel dans les sources applicatives/validation.
Cela complète l'allowlist du contexte ; ce n'est pas une garantie universelle d'un scanner de secrets.

Depuis un dossier local neuf, après contrôle des hashes et import de la base :

```powershell
docker load --input <archive-base-decompressee.tar>
# Exiger l'ID 5c541f... puis créer ce tag local pour FROM :
docker image tag sha256:5c541f103b62672bc5551aa093f840c878c7129797665ed63d2f0db5a874e16e aura-security-lot1:prepared-tests-20261017-amd64
# Extraire le contexte vérifié dans <contexte-neuf>.
docker buildx build --no-cache --platform linux/amd64 --network none --pull=false --provenance=false --output type=docker,dest=<nouvelle-archive.tar>,rewrite-timestamp=true --build-arg SOURCE_DATE_EPOCH=1792271645 --build-arg SOURCE_MANIFEST_SHA256=5ea8d711913fc1f52a338c98f92ad3461a1468ef5590290369f1039d03ac15fe -t aura-security-lot2:normalized-sec0024-amd64 <contexte-neuf>
docker load --input <nouvelle-archive.tar>
docker image inspect aura-security-lot2:normalized-sec0024-amd64 --format '{{.Id}} {{.Os}}/{{.Architecture}}'
```

Exiger l'identité finale ci-dessus. Deux exports normalisés, dont une reconstruction sans
cache, ont produit le **même manifest OCI et la même configuration**. SOURCE_DATE_EPOCH seul
n'avait pas suffi ; l'export vers archive avec `rewrite-timestamp=true` a corrigé ce point.
L'export direct vers le moteur avait refusé rewrite-timestamp avec unpack ; aucun succès
de cette tentative n'est revendiqué. [Options officielles Docker](https://docs.docker.com/build/exporters/oci-docker/).

Tests exécutés sur **l'ID final**, sans aucun montage source ou test :

```text
docker run --rm -i --network none --platform linux/amd64 --entrypoint python <ID-final> -B - < verify_image.py
docker run --rm --network none --platform linux/amd64 -e TESTING=1 -e SUPABASE_URL=https://synthetic.supabase.invalid -e SUPABASE_ANON_KEY=SYNTHETIC_PUBLIC -e SUPABASE_SERVICE_ROLE_KEY=SYNTHETIC_ADMIN -e ENABLE_STARTUP_RECOVERY=false -e ENABLE_CACHE_BACKFILL=false -e AURA_REPOSITORY_ROOT=/opt/aura-validation -e PYTHONPATH=/app:/opt/aura-validation --entrypoint python <ID-final> -B -m pytest -p no:cacheprovider /opt/aura-validation/tests -q --tb=short
```

Résultats : pip check/requirements/empreintes valides ; **233 tests en39.36s**, aucun ignoré,
un warning Starlette/httpx. Le premier candidat avait omis le script de consolidation nécessaire
à la collecte d'un test historique ; contexte corrigé, script ajouté dans la zone de validation.
Aucun script de consolidation/backfill/recovery n'a été exécuté.
Le backend exact a aussi été testé en HTTPS `127.0.0.1:18444`, TLS seuls binds autorisés,
tmpfs et aucun montage source, contre le Supabase dédié : **18 groupes intégrés réussis**,
JWT ES256 réels, SQL/PostgREST, concurrence/atomicité, pull positif, streaming, refresh,
expiration naturelle401, logout et refresh refusé. Aucun nouveau compte nécessaire.
Les sessions HS256/RS256 production et UI Android/Desktop/Web ne sont pas validées ici.

Quatorze tests opérationnels locaux réussis (9.81s) : dump custom/TOC/restauration synthétique PostgreSQL17,
migration protégée sans altération, rollback de la totalité du schéma en cas d'altération injectée,
refus d'un SQL source changé, détection d'un seul grant CRUD manquant, sonde production offline
par défaut, identité cible exacte, JSON Caddy/inode stricts et six refus du mode Python optimisé.
Le premier restore a rencontré `public` déjà présent : la
procédure supprime désormais **uniquement le schéma vide d'une base locale neuve**, sans CASCADE.
Ce test ne restaure aucun backup ni Auth Supabase réel de production.

## Préflight initial de production réellement exécuté, avant la fenêtre

I1 puis I2 ont été relancés après confirmation des droits SELECT complets sans filtrage RLS.
PostgreSQL17.6, 20 tables public/RLS, 13 policies permissives, aucune fonction/vue public,
processed_batches toujours absent : I3 non exécuté. Incohérences7/7 à zéro ; volumes identiques
au précédent inventaire : profils3, playlists4, items251, historique9843, sessions/events0,
opérations21795. Contraintes/ACL/dépendances sont à comparer au reçu I1 ; aucun compte privé lu.
Ces observations ne sont pas un snapshot garanti jusqu'à M4 ; refaire I1/I2 sous maintenance.

Data API : `public`200 sur limit0 ; `graphql_public`404/PGRST205 pour une table inexistante,
donc schéma accepté ; les sept autres schémas inventoriés, dont auth/storage/vault,406/PGRST106.
La réponse au nom de schéma fictif reflétait ce nom et ne listait pas les schémas : aucune
conclusion n'est tirée de ce premier parsing. Les sondes des neuf noms réellement inventoriés
confirment l'exposition observée ; aucune configuration Data API changée.
HTTPS prod santé200, privé anonyme401, /test404 ; dev503. Pas de sonde authentifiée production.

Audit SSH lecture seule : API lot1 `845680a8...` healthy, APP_ENV production, debug/routines
false, commande logging sûre, aucun port API publié ni socket hôte8000/8001 ; Caddy actif
conforme au fichier monté/release, hash `beb9c32353c174270adec49fb6840a437ad353e26de5a366598c99a194bc4586`,
inode `2049:1310859`, json-file10m/3 sur API/Caddy, environ114Go libres, NTP synchronisé.
Dev est exited, absent du profil par défaut. **Son ancien conteneur arrêté conserve une
configuration historique de publication** : ne jamais le démarrer, et ne pas présenter son
PortBindings historique comme une écoute active. Les cinq fichiers Compose doivent empêcher
toute réactivation ; l'état arrêté est un gate avant/après chaque transition.
Trois mesures sur canal SSH établi bornent l'écart VPS–poste ; meilleur aller/retour17.6ms,
VPS en avance de1.3885 à1.4061s. NTP=true et cet écart ne prouvent pas l'alignement avec
l'émetteur Auth : refaire la mesure et valider une session réelle en M7. Le +0.842s observé
en SEC-002.3 justifie ce contrôle. Le store DPAPI du compte dédié existe localement ; il
n'a été ni déchiffré ni utilisé contre production pendant SEC-002.4.

## Sauvegarde et restauration — gate M2 simplifié

Appliquer exclusivement [SEC-002.5](security-lot2-recovery.md) : un seul jeu chiffré S,
récent, contenant public/auth/supabase_migrations et les dépendances nécessaires, dont
la restauration privée des octets exacts est vérifiée. Ne pas réinstaller les rôles globaux
ou toute l'infrastructure Supabase. Les UUID utilisateurs et liens Auth/profils sont conservés.
Le risque de perte des modifications S→restauration est explicitement accepté par l'opérateur.
Aucun RPO0, gel global ou deuxième sauvegarde B0/B1 n'est imposé.

Après autorisation M2-S : connexion existante TLS verify-full, PG17 et age locaux, dossier
hors dépôt chiffré et protégé, restauration sans réseau et reçu expurgé avec heure T/hash.
Pas de dump/secret installé sur le VPS ; ses outils16.15 ne conviennent pas pour la source17.6.
Aucun export privé n'est autorisé par la simplification seule. Aucune restauration production
ou migration inverse n'est déduite de ce test. Garder volumes, configuration et protections lot1.
La durée réelle de sauvegarde/restauration se mesure avant la maintenance d'environ30min.

## M0–M8 — commandes et points d'arrêt

Les commandes suivantes ne sont pas un script automatique. Après chaque gate : conserver les
preuves expurgées, confirmer la reconnexion SSH et attendre les autorisations de transition.
Interruption acceptée environ30min ; backup/restauration et prépositionnement avant le décompte.
Si une validation échoue, prolonger la maintenance et demander une décision humaine.

### M0 — prépositionnement, après autorisation distincte de transfert/import

Staging proposé `/root/aura-sec0024-staging`, root0700. Transférer exclusivement archive RC,
paquet `security-lot2-ops.tar.gz`, `candidate-manifest.json` et `SHA256SUMS-vps` depuis la
livraison vérifiée ; jamais .env/credentials. Le paquet contient la migration originale,
son wrapper et les outils. Ne rien extraire automatiquement dans `/opt/aura`.

```bash
set -euo pipefail
cd /root/aura-sec0024-staging
sha256sum --check --status SHA256SUMS-vps
python3 -B - <<'PY'
from pathlib import Path
import tarfile
with tarfile.open('security-lot2-ops.tar.gz','r:gz') as archive:
    entries=archive.getmembers()
    assert all(e.isfile() and e.name==Path(e.name).name and not e.name.startswith('.') and e.size<2_000_000 for e in entries)
    for entry in entries:
        with Path(entry.name).open('xb') as target:
            target.write(archive.extractfile(entry).read())
print('operator_package_extracted_to_staging_only')
PY
gzip -dc security-lot2-rc-amd64.tar.gz | docker load
docker image inspect sha256:3dedda8e3518d5f12ffc65f7765d92310afa46d83b63e006bbec61fdbd619bb2 --format '{{.Id}} {{.Os}}/{{.Architecture}}'
docker run --rm -i --network none --platform linux/amd64 --entrypoint python \
  sha256:3dedda8e3518d5f12ffc65f7765d92310afa46d83b63e006bbec61fdbd619bb2 -B - < verify_image.py
```

**STOP** si identité/hashes/dépendances divergent. Aucun service activé, aucun transfert réalisé
par cette section seule. L'exécution autorisée est consignée en fin de document ; conserver
le staging utilisé par les cinq Compose ci-dessous.

### M1 — baseline et accès

Deux connexions SSH indépendantes, dont une de secours conservée ouverte, et une nouvelle
connexion BatchMode réussie. Secours console chiffré facultatif dans ce périmètre sans changement
SSH/pare-feu, conformément à la décision lot1 ; ne pas utiliser VNC non chiffré pour le mot de passe root.

```bash
cd /opt/aura
export AURA_SECURITY_IMAGE=sha256:3dedda8e3518d5f12ffc65f7765d92310afa46d83b63e006bbec61fdbd619bb2
dcr() {
  docker compose -p infra -f infra/docker-compose.vps.yml -f infra/docker-compose.vps.caddy.yml \
    -f infra/security-lot1/compose.containment.yml -f infra/security-lot1/compose.release.yml \
    -f /root/aura-sec0024-staging/compose.candidate.yml "$@"
}
dcr config --quiet
timedatectl show --property=NTPSynchronized --value
date -u +%s.%N
test "$(docker inspect --format '{{.State.Running}}' aura-api-dev)" = false
test "$(docker inspect --format '{{.State.Status}}' aura-api-dev)" = exited
```

Comparer la baseline au reçu VPS lecture seule ; vérifier la configuration effective à liste
positive, jamais `docker inspect`/`compose config` brut vers le poste. Exiger : aucun bind code,
aucun port API, même volume audio, commandes/logs/flags lot1 conservés, SDK2.33 et cible Supabase
production publique correcte. NTP=false, dérive/SSH perdu, dev actif ou volume différent : **STOP**.
Ne pas utiliser un Compose de base seul et ne lancer aucun profil dev.

### M2 — sauvegarde et restauration vérifiée

Après autorisation M2-S : exécuter le protocole unique SEC-002.5 et restaurer S une fois.
Contrôler son reçu, son hash exact, l'heure T et son âge avant M3. **STOP sans reçu réel conforme**.
Les écritures ultérieures ne déclenchent pas un dump B1 obligatoire : leur perte lors d'une
reprise est acceptée. Si le jeu est ancien, présenter l'intervalle de perte, puis conserver S
ou proposer son remplacement ; aucune seconde validation intégrale imposée par défaut.

### M3 — maintenance et arrêt des écrivains backend

#### Périmètre exact à autoriser ultérieurement

Cible : VPS `root@212.90.121.80`, projet `/opt/aura`, projet Compose `infra`, conteneurs
`aura-caddy` et `aura-api` uniquement. Le service `aura-api-dev` doit rester `exited`.
M3 ne transfère ni importe d'image, ne recrée aucun conteneur et n'applique aucun SQL.
Lors de la revue préparatoire, ces commandes n'étaient pas exécutées. La fenêtre autorisée
qui a suivi est consignée en fin de document ; aucune nouvelle exécution implicite.

**Conditions avant la première écriture distante** :

- P0/M0 autorisé et terminé, hashes et ID RC exacts. Condition satisfaite lors de la fenêtre
  décrite ci-dessous ; à recontrôler avant toute future intervention autorisée.
- M1 refait au moment de l'intervention : deux SSH indépendants sans multiplexage, secours
  conservé ouvert et reconnexion neuve réussie ; baseline lot1, cinq Compose, NTP et confinement
  conformes. Aucun SSH/pare-feu/DNS ou secret à modifier.
- Reçu M2-S réel conforme, jeu S et clé disponibles, âge présenté à l'opérateur. Garder le S
  vérifié ; aucun deuxième dump/restauration imposé. Aucun nouveau contrôle distant exécuté
  pendant la revue préparatoire ; les contrôles réels de la fenêtre figurent au bilan.
- Pas d'autre opérateur/job administratif écrivant durant la fenêtre. Caddy ne bloque pas
  les accès directs Auth/Data API Supabase : M3 arrête les écrivains **du backend AURA**,
  sans prétendre geler tout Supabase. La perte depuis S reste acceptée.

| Étape | Opération précise | Contrôle et condition d'arrêt |
|---|---|---|
| M3.1 | Dossier opérateur root0700 ; copie du Caddyfile et conservation inode/hash | Copie strictement identique et état release actif conforme ; sinon STOP avant coupure |
| M3.2 | Validation candidat maintenance, écriture en place, synchronisation, validation et reload | Même inode, hash hôte/bind et JSON actif exactement conformes ; écart après substitution : repli fermé |
| M3.3 | Sondes extérieures anonymes et SSH neuf | Prod `/me/playlists` et `/test`503, dev503, aucun HTTP8000/8001 ; sinon repli fermé puis STOP |
| M3.4 | Arrêt de `aura-api` avec délai30s, confirmation prod/dev | Tous deux Running=false/Status=exited ; état inconnu, redémarrage ou arrêt forcé : STOP et diagnostic |
| M3.5 | Recontrôle maintenance/confinement/secours, reçu expurgé | Horodatage, inode/hashes, mode Caddy, états et sondes ; STOP avant M4 et attendre décision |

**Repli fermé** si maintenance non démontrée après substitution : arrêter `aura-caddy`,
puis `aura-api` et vérifier leurs états. Le site peut ne plus répondre en HTTPS, au lieu du503.
Ce repli doit être explicitement inclus dans l'autorisation M3. Si SSH est perdu, ne pas
prétendre avoir exécuté le repli : état distant inconnu, arrêt et décision via le secours.
Aucun redémarrage ni restauration automatique du Caddyfile release ou du backend ; la copie
sauvegardée reste une référence. Un échec avant substitution arrête M3 sans déclencher de coupure.

**Risques et durée** : streaming interrompu au plus tard à l'arrêt API ; sync, téléchargements,
jobs et OTA indisponibles, requêtes en cours éventuellement interrompues. Les transactions SQL
déjà terminées restent commises ; pas d'annulation globale promise. Un reload défectueux peut
laisser l'ancienne configuration active, d'où le contrôle JSON et le repli fermé. L'écriture
en place préserve le bind mount, sans remplacement par rename. Ni SSH ni réseau ne changent.
Après arrêt API, `/health` peut devenir502/503 : attendu, sans remplacer les sondes des autres routes.
La réserve IPv6 lot1 reste explicite : un timeout sans connectivité IPv6 démontrée ne prouve
pas un filtrage. Ne changer aucune règle pour la lever.

Prévoir environ2–5min pour M3 après les prérequis, estimation non mesurée en production.
**Les30min acceptées concernent M3–M8 dans leur ensemble**, pas une durée garantie avec M3 seul :
l'API demeure arrêtée jusqu'aux décisions suivantes. Si un gate échoue, prolonger la maintenance
et demander une décision, jamais réexposer un backend vulnérable ou incompatible pour tenir le délai.

#### Commandes préparées, après autorisation explicite M3

Autorisation explicite de fenêtre incluant reload Caddy, arrêt API et arrêt de secours Caddy
si la maintenance ne peut pas être établie. Sauvegarder Caddy avant écriture, sans casser le bind :

```bash
# Même shell que M1 : /opt/aura, fonction dcr avec les cinq fichiers, set -euo pipefail.
umask 077
O=$(mktemp -d /root/aura-sec0024-operation.XXXXXXXX)
export O
test -f infra/caddy/Caddyfile && test ! -L infra/caddy/Caddyfile
stat -c '%d:%i' infra/caddy/Caddyfile > "$O/Caddyfile.inode"
cp --preserve=mode,ownership,timestamps infra/caddy/Caddyfile "$O/Caddyfile.before"
sha256sum "$O/Caddyfile.before" > "$O/Caddyfile.before.sha256"
cmp -s infra/caddy/Caddyfile "$O/Caddyfile.before"
sha256sum --check --status "$O/Caddyfile.before.sha256"
python3 -B /root/aura-sec0024-staging/caddy_state.py --expect release --inode-file "$O/Caddyfile.inode"

printf '%s\n' \
  'a6168d7eb49e6dd6d6d3f130014eb9b6dbcf1207e9a5363dc61786040b65015b  infra/security-lot1/Caddyfile.containment' \
  'beb9c32353c174270adec49fb6840a437ad353e26de5a366598c99a194bc4586  infra/security-lot1/Caddyfile.release' \
  | sha256sum --check --status
docker exec -i aura-caddy caddy validate --config /dev/stdin --adapter caddyfile < infra/security-lot1/Caddyfile.containment
cat infra/security-lot1/Caddyfile.containment > infra/caddy/Caddyfile
sync -f infra/caddy/Caddyfile
test "$(stat -c '%d:%i' infra/caddy/Caddyfile)" = "$(cat "$O/Caddyfile.inode")"
cmp -s infra/caddy/Caddyfile infra/security-lot1/Caddyfile.containment
docker exec aura-caddy caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
docker exec aura-caddy caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile
python3 -B /root/aura-sec0024-staging/caddy_state.py --expect containment --inode-file "$O/Caddyfile.inode"
```

Comparer hash hôte/bind et JSON actif au JSON adapté du candidat maintenance ; contrôle exact,
pas recherche de sous-chaîne. Depuis l'extérieur : `/me/playlists` et `/test`503, dev503, ports
8000/8001 sans HTTP ; SSH neuf fonctionne. Sinon arrêt de secours `docker stop aura-caddy`,
puis `dcr stop --timeout 30 aura-api`, seulement dans le périmètre de repli autorisé ; vérifier
les états arrêtés puis **STOP** et décision humaine. Ne pas enchaîner M4 ; aucune réouverture ancienne.

```bash
dcr stop --timeout 30 aura-api
test "$(docker inspect --format '{{.State.Running}}' aura-api)" = false
test "$(docker inspect --format '{{.State.Status}}' aura-api)" = exited
test "$(docker inspect --format '{{.State.Running}}' aura-api-dev)" = false
test "$(docker inspect --format '{{.State.Status}}' aura-api-dev)" = exited
docker inspect --format '{{.State.ExitCode}}' aura-api
```

API arrêtée = streaming/sync/jobs/OTA indisponibles ; santé peut devenir502/503 durant cet
arrêt car Caddy maintenance proxifie /health. Ce comportement attendu n'autorise pas une
réouverture. Aucun restart policy, pare-feu ou secret modifié. Confirmer absence d'autre
écrivain/admin pendant la fenêtre ; le wrapper M4 bloquera aussi les écritures Data API
sur les tables existantes pendant la transaction.

Sondes extérieures préparées (poste opérateur, sans token, TLS vérifié) :

```powershell
curl.exe -q --noproxy "*" --connect-timeout 5 --max-time 8 -sS -o NUL -w '%{http_code}' https://aura-prod.duckdns.org/me/playlists
curl.exe -q --noproxy "*" --connect-timeout 5 --max-time 8 -sS -o NUL -w '%{http_code}' https://aura-prod.duckdns.org/test
curl.exe -q --noproxy "*" --connect-timeout 5 --max-time 8 -sS -o NUL -w '%{http_code}' https://aura-dev.duckdns.org/
curl.exe -q --noproxy "*" --connect-timeout 5 --max-time 8 -sS -o NUL -w '%{http_code}' http://212.90.121.80:8000/health
curl.exe -q --noproxy "*" --connect-timeout 5 --max-time 8 -sS -o NUL -w '%{http_code}' http://212.90.121.80:8001/health
ssh -o BatchMode=yes -o ControlMaster=no -o ControlPath=none root@212.90.121.80 true
```

Les trois premières sondes doivent retourner503 en maintenance normale, même après arrêt API.
Les ports doivent ne fournir aucune réponse HTTP (code000 et échec de connexion), à corréler
à l'absence de publication/socket et au réseau accessible ; toute erreur curl n'est pas un succès.
En repli Caddy arrêté, l'absence HTTPS est attendue mais M3 finit en échec contrôlé, pas en
maintenance validée. Refaire IPv6 depuis une origine réellement connectée si disponible ;
conserver la réserve existante sinon. Ne jamais ajouter -k. Conserver aussi le canal SSH de secours.

**Texte d'autorisation proposé lors de la préparation, pour préciser le périmètre** :
« J'autorise M3 uniquement sur le VPS AURA212.90.121.80 : sauvegarde vérifiée du Caddyfile,
écriture en place du candidat maintenance vérifié, validation/reload Caddy, sondes anonymes,
arrêt de aura-api et confirmation de dev arrêté ; en cas d'échec après substitution, arrêt de
secours de aura-caddy puis de aura-api et maintien de la fermeture. Aucun redémarrage,
recréation, changement réseau/secret, SQL/migration ou réouverture. Arrêt avec reçu avant M4. »
Ce texte n'autorise pas P0/M0 ; celui-ci demeure une décision distincte avant M3.

### M4 — inventaire stable puis migration unique protégée

Rejouer **I1 puis I2**, I3 seulement si la table existe, avec droits complets et READ ONLY,
via le connecteur Supabase sur `neosgkuccazvgkzwzjzh`. Comparateur/incohérences doivent passer,
aucun objet/policy/fonction/contrainte inconnu, inventaire et modes Data API revus. Toute
différence impose revue ; aucun correctif automatique. Reconfirmer le hash original et wrapper.

Le générateur `prepare_guarded_migration.py` vérifie le hash SQL original ; il conserve tout
son corps DDL/PLpgSQL, normalise CRLF et remplace seulement son BEGIN/COMMIT extérieurs.
Le wrapper verrouille les tables public existantes en ordre stable, calcule un SHA256 de
multiensemble des lignes JSONB avant/après **dans la même transaction**, vérifie les nouvelles
contraintes/RLS/RPC, puis COMMIT. Toute modification d'une ligne existante provoque rollback.
La nouvelle table processed_batches doit être vide si elle n'existait pas. Aucune ligne/empreinte
privée n'est retournée, seulement le nombre de tables inchangées. Timeouts5s/60s conservés.

Après la **seule autorisation nominative M4**, appel configuré exact :

```text
supabase.apply_migration(
  project_id = "neosgkuccazvgkzwzjzh",
  name = "secure_user_data_isolation",
  query = contenu vérifié de apply-sec0024-guarded.sql
)
```

Le nom source reste `20261017200822_secure_user_data_isolation.sql` ; le connecteur attribue
sa version d'historique, comme dans le projet isolé. Conserver nom/version/reçu et hashes.
Ne jamais lancer db push/reset, toutes les migrations, un script de réparation, ou rejouer
après une perte réseau avant revue de l'état/historique. **STOP** sur erreur ; API reste arrêtée.

### M5 — validation SQL après COMMIT

Exécuter `postcheck.sql` puis les comptes I2/I3 READ ONLY. Critères : 11 tables privées RLS
et 11 guards restrictifs (adapter seulement après revue d'objets optionnels), authenticated CRUD
complet sans TRUNCATE/REFERENCES/TRIGGER, anon/jobs refusés, FK composites validées, PK marqueurs
`(user_id,batch_id)`/`(user_id,operation_id)`, positions différables/immediate, RPC invoker avec
EXECUTE authenticated et pas anon. SHA256 de définition RPC normalisée :
`3094a8ddebbac3ec8a80bd1cba962f4fe56e6574d3bd9fa6b6d5032ec2e17863`.
Recevoir la preuve du wrapper pour **toutes les tables préexistantes** ; comparer volumes et
incohérences à l'état M4. Des écritures directes légitimes après COMMIT peuvent faire varier
les comptes : diagnostiquer, ne pas masquer une divergence. **STOP** sur contrôle absent/faux.

### M6 — backend compatible seulement, proxy encore en maintenance

```bash
dcr up -d --no-deps --no-build --pull never --force-recreate aura-api
test "$(docker inspect --format '{{.Image}}' aura-api)" = "$AURA_SECURITY_IMAGE"
docker exec aura-api python -B -c 'import urllib.request; assert urllib.request.urlopen("http://127.0.0.1:8000/health",timeout=5).status==200; print("health_ok")'
docker exec -i aura-api python -B - < /root/aura-sec0024-staging/verify_image.py
```

Exiger healthy puis empreintes embarquées, SDK, flags/logs/rétention, volume et aucun bind
code/port ; aucun démarrage de dépendances/dev ni reconstruction distante. Refaire contrôle
SSH/maintenance extérieur. Échec : maintenir confinement ; **aucun retour à lot1/ancien backend**,
incompatible avec nouvelles PK/RPC et encore vulnérable au lot2.

### M7 — vérifications internes et session positive dédiée

Après autorisation des sessions du compte lot1 dédié et de ses deux fichiers audio temporaires,
sonde depuis le checkout Windows figé, aucun secret en argv, stdout ou rapport :

```powershell
server/.venv/Scripts/python.exe -B server/ops/sec0024/production_probe.py --route internal --execute-production
```

Elle exige l'ID final, utilise le store DPAPI existant, login/refresh, GET /me/playlists200,
streaming200/206 avec octets attendus, invalide401 et /test404, puis logout et retrait exact
des deux fichiers ; journal d'intention protégé conservé sur VPS. Aucun compte/profil/playlist
SQL créé, aucune donnée privée d'autre compte lue. Sans flag : offline, aucun credential lu.
Les anciennes sondes Windows lot1 ont une allowlist d'images : ne pas les lancer aveuglément
contre lot2 et ne pas retirer leur contrôle d'identité. Le nouveau wrapper prépare la bonne cible.

Contrôler aussi `/me/playlists` sans token/invalide401 et `/test`404 depuis l'API interne.
Sonde logs exclusivement synthétique, journaux restent sur place :

```bash
python3 -B /root/aura-sec0024-staging/target-log-check.py
```

Exiger rédaction API/Caddy et traces préservées ; pas de données réelles dans les sondes logs.
RLS/RPC ont été vérifiées en M5 et bout en bout isolé ; aucun test intercompte offensif en prod.
Tous ces contrôles et un SSH neuf doivent passer avant d'autoriser M8, sinon **STOP**.

### M8 — réouverture autorisée distinctement

```bash
docker exec -i aura-caddy caddy validate --config /dev/stdin --adapter caddyfile < infra/security-lot1/Caddyfile.release
cat infra/security-lot1/Caddyfile.release > infra/caddy/Caddyfile
sync -f infra/caddy/Caddyfile
test "$(stat -c '%d:%i' infra/caddy/Caddyfile)" = "$(cat "$O/Caddyfile.inode")"
cmp -s infra/caddy/Caddyfile infra/security-lot1/Caddyfile.release
docker exec aura-caddy caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
docker exec aura-caddy caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile
python3 -B /root/aura-sec0024-staging/caddy_state.py --expect release --inode-file "$O/Caddyfile.inode"
```

Exiger hash hôte/bind et JSON actif exact, puis contrôles **extérieurs** : santé200, privé
anonyme401, /test404, dev503, ports directs sans HTTP et nouvelle connexion SSH ; vérifier IPv6
depuis une origine réellement IPv6 si disponible. La réserve réseau IPv6 du lot1 reste explicite.
Session dédiée/streaming public sur l'image finale et nouveau contrôle des logs :

```powershell
server/.venv/Scripts/python.exe -B server/ops/sec0024/production_probe.py --route public --execute-production
```

Échec : remettre **en place** le Caddyfile maintenance validé, reload autorisé, ou arrêt de
secours Caddy autorisé ; maintenir le nouveau schéma/backend arrêté ou confiné et demander
une décision. Les sauvegardes de configuration ne sont pas un repli public autorisé.

## Autorisations distinctes et clôture

1. **P0/M0** : transfert/import des seuls artefacts nommés, contrôle des hashes/identités ;
   aucune activation. Accordé et exécuté pendant la fenêtre ci-dessous.
2. **M2-S** : lecture/export de public/auth/supabase_migrations et métadonnées nécessaires
   vers le stockage age/ACL/rétention7jours nommé dans SEC-002.5, une restauration privée locale,
   puis nettoyage ; perte des modifications après T acceptée, aucun secret/rôle production modifié.
3. **M3** : fenêtre de maintenance, reload Caddy, arrêt API et secours Caddy si nécessaire.
4. **M4** : « La migration `20261017200822_secure_user_data_isolation.sql`, exécutée via
   `apply-sec0024-guarded.sql`, doit être appliquée à AURA production
   (`neosgkuccazvgkzwzjzh`). Veux-tu que je l'exécute ? »
5. **M6/M7** : recréation uniquement aura-api sur l'ID final ; sessions de l'ancien compte
   dédié et deux fichiers synthétiques exacts, nettoyage et contrôles sans données privées.
6. **M8** : réouverture Caddy après gates réussis et contrôles extérieurs, deuxième sonde
   positive/cleanup ; autorisation du retour maintenance/arrêt de secours en cas d'échec.

Après COMMIT : réparation en avant seulement sur candidat revu, aucune migration inverse ou
restauration destructrice improvisée. Une restauration production reste une décision distincte
nommant backup/cible/perte de données potentielle ; elle n'autorise jamais l'ancien backend.
Les configurations persistantes utilisées pour toute recréation future doivent conserver les
**cinq** fichiers et l'ID lot2. Pas de modification de version/client, ni réactivation des routines.

Durée prévue après tous prérequis : 20–30min, prolongation acceptée si nécessaire. Cette durée
ne couvre pas une restauration ni un backup non préparé. La fenêtre ci-dessous est terminée.
**Aucune action externe supplémentaire pour activer le lot2.** Aucune nouvelle migration,
réouverture ou intervention n'est autorisée par ce constat.

## Exécution autorisée du2026-10-18 — SEC-002.4-RUN

L'opérateur a demandé « applique la maintenance puis la suite des étapes nécessaires », puis
répondu « Oui, autoriser cette migration dans la fenêtre prévue » à la demande nominative
`20261017200822_secure_user_data_isolation.sql`, via `apply-sec0024-guarded.sql`, sur
`neosgkuccazvgkzwzjzh`. P0/M3/M6/M7/M8 et le repli fermé étaient compris dans la fenêtre
documentée ; aucun élargissement réseau, secret, version, client ou lot3.

| Étape réellement exécutée | Preuve / résultat |
|---|---|
| P0 | Quatre fichiers transférés au staging0700, empreintes exactes ;17 membres opérateur vérifiés et97 fichiers embarqués conformes ; image AMD64 `3dedda8e...` importée, sans activation à ce stade |
| M1/M2 | Deux SSH sans multiplexage plus reconnexions neuves ; baseline lot1 et NTP conformes ; cinq Compose validés sans exposition/bind source/dev ; disponibilité et empreintes du S et de sa clé revérifiées, sans nouvel export ; session synthétique ES256/JWKS, refresh/logout réussis |
| M3 | Maintenance20:34:03.668267UTC ; backup Caddy vérifié, inode2049:1310859 conservé, JSON actif exact ;503 extérieurs et ports sans HTTP ; API arrêtée proprement20:34:53.355184UTC, dev exited |
| M4 | I1/I2 relancés sous maintenance : aucune dérive et7 incohérences à zéro. Data API identique au périmètre revu. Wrapper figé appliqué avec succès ; historique `20261018203759 / secure_user_data_isolation` |
| M5 | Postcheck exactement identique au reçu isolé :11 tables RLS,11 guards, CRUD/anon/jobs, PK composées, FK validées, positions différables et RPC invoker/hash conformes ; I2 inchangé et processed_batches vide/sans orphelin |
| M6 | Backend healthy20:39:49.071099UTC sur ID exact, Python3.12.15/SDK2.33.0,97 empreintes ; aucun bind code/port, volume existant conservé, debug/routines false, json-file10m/3 ; dev arrêté |
| M7 | Compte synthétique existant : login/refresh, GET propre200, streaming200/206, invalide401, /test404, logout ; deux fichiers créés puis retirés ; marqueur absent des logs et traces préservées |
| M8 | Release20:41:22.991880UTC ; inode/bind/JSON exacts, santé200/privé401/test404/dev503, ports directs sans HTTP et SSH neuf ; nouvelle session/streaming public réussis, deux fichiers retirés et logout ; sonde logs finale réussie |

Le wrapper a comparé counts et SHA256 de toutes les20tables public préexistantes avant/après
dans la même transaction. `apply_migration` retourne `success:true` sans retourner le SELECT
du compteur : ne pas présenter un compteur SQL non reçu. Le COMMIT réussi du wrapper figé
implique le passage de ses assertions d'intégrité ; les comptages séparés sont également inchangés.
Les valeurs et empreintes de lignes privées ne sont jamais publiées.

Rapport expurgé : `scratch/sec0024/window/execution-report.md`, JSON adjacent et reçus par étape.
Les36artefacts de livraison restent inchangés ; pas de rebuild, push ou changement de version.
Les deux canaux SSH ont été fermés proprement après les contrôles finaux.

Pour toute future opération Compose autorisée, utiliser le wrapper épinglé :
`/root/aura-sec0024-operation.0k769f26/compose-release.sh`.
Il fixe l'ID lot2 et les cinq fichiers ; les labels du conteneur actif confirment les cinq.
**Ne supprimer ni ce dossier opérateur ni `/root/aura-sec0024-staging`** tant que cette
configuration les référence. Ne pas utiliser le Compose de base seul ou les anciens quatre fichiers.

Limites : réserve de connectivité IPv6 lot1 inchangée (timeouts non assimilés à une preuve de
filtrage), pas de nouveau test UI Android/Desktop/Web, ni sessions production HS256/RS256 ou
nouvelle attente d'expiration naturelle durant cette fenêtre. JWT réel ES256 et vérifications
Auth/streaming exécutés ; A/B/concurrence/atomicité restent les preuves isolées précédentes,
sans tests offensifs sur les comptes privés. Les sondes logs synthétiques ne simulent pas toutes
les erreurs fournisseur. Audio/OTA et secrets existants préservés, dev/recovery/backfill non réactivés.


## Actualisation après SEC-003.3 — 21 octobre2026

Le backend lot3 est désormais actif ; les commandes et le wrapper lot2 ci-dessus
décrivent l’historique. **Ne plus utiliser l’ancien wrapper lot2 pour recréer l’API.**
Wrapper courant : `/root/aura-sec0034-operation.hclub2d3/compose-release.sh` avec les
cinq fichiers historiques plus `/root/aura-sec0033-staging-20261021T140944Z-cbcf1979/compose.candidate.yml`.
Conserver `/root/aura-sec0024-staging`, encore référencé comme cinquième fichier.
Image actuelle et contrôles : [bilan SEC-003.4](security-lot3-preflight.md).
Cette actualisation n’autorise aucune nouvelle intervention ou migration.
