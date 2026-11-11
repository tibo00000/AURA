# SEC-002.3 — Intégration Supabase isolée

SEC-002.4 valide désormais aussi une image autonome avec les mêmes 18 groupes, sans montage
du code ; candidat et runbook de production : `docs/ops/security-lot2-deployment.md`.

## Validation réelle terminée — 2026-10-18

**GO pour la validation intégrée isolée SEC-002.3 ; NO-GO production avant les gates M0–M8
et autorisations distinctes du runbook SEC-002.1/2.2.** Cible unique :
`nknbuhccnvvhrghvhmll`, jamais Supabase production ni VPS.

| Contrôle réellement exécuté | Résultat |
|---|---|
| Accès et configuration | ACL protégées, certificats valides, SQL TLS verify-full ; inscription publique désactivée, email/password actif, JWKS ES256 |
| Création Auth Admin | Deux comptes synthétiques A/B créés ; credentials seulement dans le manifeste protégé, aucune sortie de secrets |
| Backend local | Python3.12/Linux amd64, SDK2.33.0, image `sha256:5c541f103b62672bc5551aa093f840c878c7129797665ed63d2f0db5a874e16e` ; sources revues montées read-only, aucun `.env` embarqué sous /app, port127.0.0.1:18443, debug/recovery/backfill false |
| Chaîne Auth → FastAPI → SDK → PostgREST → PostgreSQL17.11 | Runner terminé avec status passed, **18 groupes de contrôles**, aucune étape ignorée |
| Propriété et cohérence | CRUD A/B, refus REST/RPC intercomptes, collisions/upserts, FK/RLS/EXECUTE, idempotence, bootstrap/push/pull positif et permutations réussis |
| Concurrence et erreurs SQL | Deux requêtes REST/RPC réellement observées en attente du verrou parent ; erreur P0001 et rollback ajout/retrait/PATCH vérifiés |
| Sessions et lot1 | JWT réels ES256 acceptés ; refresh réussi, expiration naturelle puis401, logout204 puis refresh refusé ; token invalide401, /test404 |
| Audio synthétique | Upload201, Bearer200 avec octets exacts, query token/Range206 avec octets exacts, absence de token401, suppression réussie |
| Journaux locaux | Sondes exclusivement synthétiques401/404 ; zéro marqueur, `token=` ou motif JWT dans les logs inspectés, 105 accès et 7 exceptions structurées au contrôle intermédiaire ; contrôle final également nul |
| Tests locaux après ajustement du runner | **22 tests ciblés Windows** en0.83s ; **233 tests backend Python3.12/Linux** en42.30s, réseau none, un avertissement Starlette/httpx, aucun échec/ignoré |
| Fin de test | Zéro schéma/trigger temporaire et zéro playlist/item restant, confirmés par SQL READ ONLY ; backend/réseau Docker local retirés et zéro conteneur de ce projet restant |

Les 18 groupes réussis couvrent contrat API, préflight SQL, login/bootstrap/création A et B,
REST étranger, batch mixte, sessions/historique/events, SDK/RLS, bootstrap/pull concurrents,
idempotence, Data API/RPC/upserts, pull positif, verrou/atomicité, streaming/auth lot1,
CRUD/suppressions multiples, refresh, expiration réelle et logout/révocation du refresh.

Commandes exécutées avec les chemins protégés décrits plus bas (`<python-outils>` désigne
`tools-venv/Scripts/python.exe`, `<run>` le dossier physique du test) :

```text
Set-TestAccess.ps1 -CheckOnly -RunDirectory <run>
<python-outils> -B server/scripts/provision_sec002_accounts.py --manifest <run>/manifest.json --backend-env <run>/backend.env --execute-isolated
docker compose -f <run>/compose.yml config --quiet
docker compose -f <run>/compose.yml up -d --no-build --pull never backend
<python-outils> -B server/scripts/verify_sec002_integrated.py --manifest <run>/manifest.json --execute-isolated
server/.venv/Scripts/python.exe -B -m pytest -p no:cacheprovider server/tests/test_sec0023_preparation.py -q --tb=short
docker run --rm --network none --platform linux/amd64 [montages read-only et environnement synthétique décrits ci-dessous] --entrypoint python sha256:5c541f103b62672bc5551aa093f840c878c7129797665ed63d2f0db5a874e16e -B -m pytest -p no:cacheprovider tests -q --tb=short
docker compose -f <run>/compose.yml down --timeout 10
```

Échecs initiaux conservés : premier essai arrêté à `api_contract`, puis contrat OpenAPI
explicitement contrôlé et conforme ; deuxième essai arrêté à `login_0`. Diagnostic réel :
Auth200 ES256/issuer/ID corrects et durée301s, route privée401 immédiatement puis200 avec
le même JWT après deux secondes. Décalage `iat` observé +0.842s ; ajustement limité au runner
documenté ci-dessous et couvert par trois nouveaux cas. Le passage final réussit sans
modifier Auth backend, handlers métier, SyncService ou migration revue.

Limites : seules les sessions **ES256** de ce projet sont validées réellement ; aucune
extrapolation aux anciennes sessions HS256/RS256 de production, à Caddy/VPS ou aux UI
Android/Desktop/Web (non relancées ici). L'image sert de runtime local avec code monté :
elle n'est pas une nouvelle image de release lot2 figée. Image finale, sauvegarde/restauration,
inventaire stable et contrôles de production restent soumis aux gates/permissions M0–M8.
Deux comptes et les fixtures catalogue/session/historique/marqueurs synthétiques sont conservés
dans ce seul projet ; aucune suppression du projet ni des credentials locaux exécutée.
Le rapport expurgé `integration-results.json` et `additional-checks-results.json` est conservé
sous `<run>`, sans JWT, mot de passe, clé serveur ou journal brut.

## Historique — initialisation autorisée du 2026-10-18

L'utilisateur a fourni le projet `nknbuhccnvvhrghvhmll` (`aura-sec0023-isolated`, eu-west-1),
puis autorisé par « go » l'initialisation, la migration nommée, les comptes/configuration Auth
et les tests de ce **seul projet**, y compris instrumentation SQL temporaire et son retrait.
Aucune autorisation production/VPS, push ou suppression du projet n'est acquise.

Exécuté et vérifié via le connecteur Supabase sur cette cible exacte :

| Étape | Résultat réellement observé |
|---|---|
| Préflight READ ONLY | PostgreSQL17.11, zéro relation public et zéro compte Auth avant écriture |
| `01-schema.sql` | Appliqué, historique distant `sec0023_isolated_schema` |
| `02-catalog-rls.sql` | Appliqué, historique distant `sec0023_isolated_catalog_rls` |
| `20261017200822_secure_user_data_isolation.sql` | Contenu exact appliqué, historique distant `secure_user_data_isolation` ; SHA256 `ffa144f458f65cd2a0ba000ee07fbbeb6faa352cf9d6316f7c9e0b40a806afa4` |
| `04-fixtures.sql` | Appliqué ; un artiste, un album et une piste synthétiques recomptés |
| `05-verify.sql` | 11 guards restrictifs, 11 tables privées RLS, RPC invoker=true/auth EXECUTE=true/anon EXECUTE=false ; GUC schémas NULL, donc aucune extrapolation de ce GUC |
| HTTPS/JWKS | HTTP200, clé publique ES256/EC, sans publier les éléments de clé |
| Data API anonyme | Table privée401, profil auth406 ; aucun test JWT propriétaire encore effectué |
| Auth public settings | email/password actif, inscription publique encore activée, email autoconfirm=false ; aucune configuration Auth modifiée |
| Accès SQL réseau | TCP5432 du projet test joignable, aucun login SQL direct tenté |
| Outillage local | Venv neuf et imports validés, psycopg3.3.6/SDK2.33 ; freeze conservé hors Git |

Le connecteur a généré ses propres versions d'historique pour les trois opérations DDL :
le nom de fichier source et son empreinte ci-dessus identifient le candidat revu appliqué.
Pas de modification de version applicative. L'ensemble SQL appliqué est celui décrit dans
la préparation ci-dessous ; aucune réparation, migration supplémentaire ou donnée privée.

Fichiers locaux réels, hors dépôt, sous ACL opérateur/SYSTEM :
`C:\Users\thiba\AppData\Local\Packages\OpenAI.Codex_2p2nqsd0c76g0\LocalCache\Local\AURA-sec0023-nknbuhccnvvhrghvhmll\run`.
Le chemin physique tient compte de la redirection Windows de Codex ; ne pas supposer que
le chemin logique AppData est visible dans un PowerShell externe. TLS local neuf, clé
publique test et manifest/Compose préparés ; **clé serveur, mot de passe DB et certificat
CA DB absents**. Aucun JWT/refresh token réel généré, affiché ou sauvegardé.

### Procédure de saisie des accès — réalisée par l'opérateur

1. Dans **le projet test uniquement**, Settings → API Keys : relever la clé serveur/secret
   (`sb_secret_...`, ou service_role legacy) ; retrouver le mot de passe DB choisi à la création.
   Ne pas utiliser ni modifier une clé ou un mot de passe de production.
2. Dans un PowerShell local, lancer la saisie masquée suivante. Elle écrit seulement dans les
   deux fichiers protégés du test, ne fait aucune connexion et ne remplace pas un champ déjà
   renseigné. Syntaxe et mode CheckOnly validés ; saisie interactive non effectuée par l'agent.

```powershell
& 'C:\Users\thiba\Desktop\AURA-workspace\server\ops\sec0023\Set-TestAccess.ps1'
```

En cas d'erreur de saisie du mot de passe PostgreSQL, relancer la même commande avec
`-ReplaceDatabasePassword`. Cette option ressaisit uniquement la copie locale du mot de
passe, conserve la clé serveur et les autres paramètres, et ne change rien dans Supabase.
Elle ne peut pas être combinée avec `-CheckOnly`.
Syntaxe, remplacement masqué et conservation de la clé/paramètres SQL vérifiés avec des
valeurs synthétiques locales ; aucune validation d'un mot de passe réel n'est impliquée.

3. Dashboard test → Database Settings → SSL Configuration → Download Certificate : enregistrer
   ce certificat public dans `run\database-ca.crt`, sous le dossier physique ci-dessus.
   Le driver exigera verify-full ; aucun assouplissement TLS ni CA globale installée.
   [Procédure officielle SSL](https://supabase.com/docs/guides/platform/ssl-enforcement).
4. Auth du test : désactiver l'inscription publique et régler l'expiration JWT à300 secondes.
   Ces réglages ne sont pas exposés par le connecteur installé : contrôle dashboard nécessaire.
   Aucun secret/rotation, fournisseur SMTP/OAuth ou réglage production à changer.
5. Prévenir l'agent une fois ces champs/réglages renseignés. Les autorisations de test sont
   déjà reçues ; aucun besoin de les redemander pour cette même cible et ce même périmètre.

**Point d'arrêt historique avant fourniture des accès, désormais levé.** Les comptes
A/B restent non créés (comptage réel0), les sessions/JWT réels, refresh/expiration/logout,
pull positif, streaming et contrôles SQL concurrents ne sont pas exécutés. La disponibilité
JWKS et les résultats SQL ne constituent pas une validation bout en bout. NO-GO production.

## Décision et autorisation

Préparer un **nouveau projet Supabase hébergé dédié**, nommé `aura-sec0023-isolated`, et un
backend local HTTPS sur `127.0.0.1:18443`. La préparation initiale n'a créé aucun objet distant ;
l'avancement après autorisation est consigné dans la section d'exécution ci-dessus.
La revue indépendante SEC-002.2 est favorable ; elle ne vaut pas validation intégrée réelle.
NO-GO production maintenu. Le backend dev/VPS et les secrets/volumes de production sont exclus.

La pile locale complète reste possible, mais ajoute le CLI absent de ce poste, les services
Auth/PostgREST/PostgreSQL et leur configuration TLS/issuer/signatures. Le projet hébergé
réduit cette maintenance et exerce les vrais services gérés. Il ne s'agit pas d'une branche
de production ni d'un clone/dump : créer un projet vide, avec ses propres clés et comptes.

Coût consulté le 2026-10-18 : Free à 0 USD si un emplacement gratuit est disponible
(limite annoncée : deux projets actifs, pause après une semaine d'inactivité). Sur une
organisation payante, un projet supplémentaire peut être facturé : demander le devis
exact avant création, ne pas changer le forfait de production. Aucun coût engagé ici.
Source : [tarification Supabase](https://supabase.com/pricing).

## Historique — création initiale du projet

1. Dashboard Supabase → New project, choisir une organisation disposant d'un emplacement
   Free ; nom `aura-sec0023-isolated`, région proche, mot de passe DB **nouveau** dans le
   gestionnaire de mots de passe. Ne pas utiliser branching, restore, import ou intégration VPS.
2. Vérifier le prix avant de valider. Si payant, s'arrêter et approuver le coût séparément.
3. Communiquer uniquement le **project ref et l'URL publique du nouveau projet**, aucun secret.
   La création peut aussi être automatisée après autorisation nominative organisation/région/coût.
4. Autoriser séparément, pour cette cible exacte, schéma/migration/fixtures, création de deux
   comptes synthétiques, tests avec écritures et instrumentation SQL temporaire, puis nettoyage.

Ne créer aucun compte de test dans AURA production. Aucune autorisation distante de SEC-002.1
ou du lot1 n'est réutilisée. L'exécution partielle autorisée est distinguée ci-dessus de la préparation.

## Ressources et séquence minimale

- Projet vide : PostgreSQL >=15, Auth email/password, Data API `public`, clés propres ES256
  recommandées ; vérifier l'algorithme effectivement retourné et l'issuer. Pas de rotation
  de clé production. JWT de test 300 secondes pour l'essai d'expiration réelle, pas moins.
- Deux comptes A/B créés via Auth Admin avec emails synthétiques confirmés, sans email envoyé,
  sans inscription publique/OAuth/SMTP de production. Mots de passe aléatoires propres au test.
- Un artiste, album et morceau synthétiques ; WAV généré en mémoire par le runner. Aucun
  fournisseur musical, download externe ou contenu personnel requis.
- Image backend Python3.12/SDK>=2.33 vérifiée, sources locales corrigées montées en lecture
  seule, stockage tmpfs séparé, TLS local éphémère, debug/recovery/backfill false et logs lot1.
- Pour observer les verrous et injecter une erreur SQL : connexion SQL administrateur du
  **projet de test**, TLS verify-full, credentials dans un fichier protégé hors Git. Aucun
  contrôle privilégié ajouté à l'API publique ni à la migration de production.

## Artefacts préparés et exécution future

`server/scripts/prepare_sec002_environment.py` est **entièrement hors ligne**. Il exige une
nouvelle destination hors dépôt, une référence de projet et la liste des hôtes de production
interdits. Il génère un certificat local neuf de sept jours (non installé dans Windows),
une configuration Compose autonome, des templates sans credentials et cinq étapes SQL :

| Ordre | Artefact généré | Gate / effet futur dans le seul projet isolé |
|---|---|---|
| 01 | `01-schema.sql` | Refuse toute table/vue public ou tout compte Auth déjà présent ; construit le schéma depuis les SQL suivis, en transaction ; grants service_role explicites pour les services catalogue |
| 02 | `02-catalog-rls.sql` | Copie du SQL lot1 : RLS catalogue, aucun grant catalogue client |
| 03 | `03-20261017200822_secure_user_data_isolation.sql` | Copie **octet pour octet** de la migration SEC-002 corrigée ; transaction/préflight/ACL/RLS/RPC invoker |
| 04 | `04-fixtures.sql` | Un artiste/album/track `sec0023-*`, sans contenu personnel ni import, en transaction |
| 05 | `05-verify.sql` | READ ONLY/ROLLBACK : version, 11 guards restrictifs, 11 tables privées RLS, RPC invoker/auth EXECUTE=true, anon EXECUTE=false, GUC des schémas si visible |

Ne pas lancer `supabase db reset`, `db push`, ni une migration automatique du dépôt complet.
Le schéma historique AURA se trouve dans plusieurs dossiers : le générateur sélectionne
explicitement les fichiers nécessaires. Les cinq empreintes sont consignées dans
`bundle-hashes.json`. Toute erreur impose l'arrêt ; pas de réparation ni nouvelle tentative
automatique sur une base partiellement initialisée. Aucun backend ni compte Auth avant 05.
Le GUC NULL ne prouve pas le schéma exposé : contrôler Dashboard → Data API → Exposed schemas,
`public` nécessaire, `auth`/`storage` non exposés ; examiner tout schéma supplémentaire.
Ne pas modifier ces paramètres dans le projet production.

### Préparation locale protégée, après identification de la cible

Créer un parent neuf hors Git avec ACL opérateur et SYSTEM uniquement ; vérifier son ACL avant
d'y placer un secret. Exemple PowerShell **préparé, non exécuté ici** :

```powershell
$testParent = Join-Path $env:LOCALAPPDATA 'AURA-sec0023-<identifiant-neuf>'
New-Item -ItemType Directory -Path $testParent -ErrorAction Stop
$operatorSid = ([System.Security.Principal.WindowsIdentity]::GetCurrent()).User.Value
icacls $testParent /inheritance:r /grant:r ("*{0}:(OI)(CI)F" -f $operatorSid) '*S-1-5-18:(OI)(CI)F'
icacls $testParent
server/.venv/Scripts/python.exe -B server/scripts/prepare_sec002_environment.py --output "$testParent/run" --project-ref <reference-test> --production-host <hote-supabase-prod> --production-host <hote-api-prod> --protected-parent-confirmed
```

Le manifeste généré est volontairement **inexécutable** : confirmation false, clé vide et
zéro utilisateur. Après contrôle du projet neuf, remplir uniquement les copies protégées :
clé publique/publishable dans manifeste et backend.env, clé serveur **du test seulement**
dans backend.env, connexion DB dans sql-control.json (host, user, password, sslrootcert).
Direct : `db.<ref-test>.supabase.co`, user postgres ; ou pooler **session port5432**, user
`postgres.<ref-test>`. Le runner refuse un autre ref, un pooler transaction6543 ou TLS sans
verify-full/certificat racine. Ne rien coller dans la conversation ni dans une commande.

Configurer Auth test : email/password, inscription publique désactivée, aucun SMTP/OAuth/
webhook/Storage/Realtime copié de production, expiration JWT300s, algorithme attendu ES256
confirmé dans le dashboard. Aucun secret HS256 production. Si un autre mode est requis,
le configurer explicitement pour **ce projet uniquement** puis vérifier sa session ; aucun
succès ES256 ne vaut preuve des anciennes sessions HS256 production.

L'opérateur utilise un venv neuf hors dépôt pour les scripts, avec les versions de
`server/ops/sec0023/requirements-tools.txt` (dont psycopg[binary]3.3.6), sans modifier le venv
ou les dépendances déployées. Préparer/installations locales automatisables, puis vérifier
imports/versions et conserver le freeze obtenu. Le driver SQL n'est pas installé sur ce
poste lors de cette préparation ; sa disponibilité doit être vérifiée avant exécution réelle.

Après autorisation nominative, je pourrai appliquer 01→05 au projet test identifié, vérifier
les résultats, puis automatiser la création des comptes avec :

```powershell
# Sans flag : préflight local uniquement, aucun réseau.
<python-outils> -B server/scripts/provision_sec002_accounts.py --manifest <manifest-protege> --backend-env <backend.env-protege>
# APRÈS autorisation des deux comptes dans le projet test : même commande + --execute-isolated
```

Les deux comptes ont des emails synthétiques uniques `sec0023-...@example.com`, confirmation
admin sans email envoyé, mots de passe aléatoires. Chaque compte créé est immédiatement
inscrit dans le manifeste protégé ; une création partielle bloque la relance et exige revue,
pas un retry créant des comptes supplémentaires. Ne jamais éditer les credentials production.

### Backend de test local

`server/ops/sec0023/compose.yml` est un template rendu par le générateur. Il ne se fusionne
avec **aucun** Compose existant : seul service backend, port127.0.0.1:18443, tmpfs pour
downloads/sync_files/updates, code/certificats read-only, pas de Docker socket ni volume
persistant. Sortie réseau vers Supabase nécessaire ; aucun accès VPS prévu. Image immuable
locale validée, pull_policy never, pas de build/pull implicite ; vérifier son SDK et absence
de fichier d'environnement embarqué avant lancement. Les sources corrigées sont montées
en lecture seule ; figer leurs empreintes avant le test.

```powershell
# Exemples futurs, depuis le dossier protégé généré ; aucune activation effectuée ici.
$env:AURA_SEC0023_IMAGE = 'sha256:<identite-locale-verifiee>'
docker ps -a --filter label=com.docker.compose.project=aura-sec0023-isolated
# Exiger zéro conteneur préexistant de ce projet, sinon arrêter pour revue.
docker compose -f compose.yml config --quiet
docker compose -f compose.yml up -d --no-build --pull never backend
```

Uvicorn emploie le factory/formatter du lot1, y compris erreurs, sans query token/header dans
les sorties ; rétention Docker10m/3. Debug/recovery/backfill false. Pas de Caddy ni domaine
public supplémentaire : le test client ajoute le certificat éphémère à son propre contexte
TLS, conserve les racines publiques et ne fait jamais verify=False. Aucune CA globale installée.
Vérifier santé/loopback/TLS, paramètres effectifs et absence de publications non locales avant
de se connecter ; ne pas déduire une propriété production de cette configuration.

## Matrice de tests réels — exécutée sur le seul projet isolé

`verify_sec002_integrated.py` reste sans réseau par défaut. `--execute-isolated` exige
maintenant le ref exact du projet, API loopback HTTPS/certificat, driver SQL et paramètres de
connexion vérifiés **avant le réseau**. Un préflight SQL READ ONLY vérifie ensuite métadonnées
et droits nécessaires avant login/fixtures. OpenAPI réel contrôlé avant login ;
GET liste des playlists et formes multipart/audio vérifiés, aucun GET privé par ID inventé.

| Contrôle préparé | Critère exigé pour succès réel |
|---|---|
| Connexion A/B et JWT | IDs dédiés attendus, algorithme configuré, issuer exact, signature réellement acceptée par FastAPI ; durée compatible avec l'essai d'expiration |
| CRUD A/B | Créer/lire/PATCH/supprimer propres playlists ; toutes occurrences de piste supprimées ; seconde suppression vide |
| REST et RPC intercomptes | A→B **et** B→A refusés ; aucune ligne étrangère obtenue/modifiée ; owner de payload sans autorité |
| Data API/ACL | public réellement accessible sous JWT, auth refusé par schéma406, catalogue client refusé, RPC propriétaire utilisable et anonyme refusé ; SQL confirme RLS restrictive/EXECUTE/invoker |
| Collisions/upserts | Upsert propre accepté, upsert ID étranger refusé, collisions ID propre/étranger et position occupée sans mutation ; permutations RPC aller/retour valides |
| Sessions/historique/events | Opérations propres et refus étrangers/bad parents, FK/RLS réelles ; marqueurs batch/operation identiques A/B indépendants et retry dédupliqué |
| Bootstrap/push/pull | Token réel bootstrap, **PATCH postérieur**, pull contient effectivement la bonne playlist/valeur et aucune donnée B/A ; 200 vide, résultat ancien ou paginé incomplet = échec |
| Concurrence REST/RPC | Connexion SQL garde verrou parent ; requêtes REST/FastAPI et RPC/SDK bloquées toutes deux, observation des bloqueurs côté PG, libération bornée puis données/timestamps cohérents |
| Atomicité SQL | Trigger invoker temporaire dans schéma non exposé par run, ciblé sur la seule playlist de test ; code P0001 vérifié puis ajout/retrait/PATCH500 sans changement ; retrait exact du trigger/fonction/schéma en finally, sans CASCADE |
| Refresh/expiration/logout | Refresh fonctionne, attendre **expiration naturelle** sans changer le token ni l'horloge, ancien JWT401 ; nouveau refresh fonctionne ; logout204 puis refresh refusé. Aucun raccourci JWT fabriqué ne vaut succès réel |
| Streaming/lot1 | WAV synthétique upload201, lecture Bearer200 exacte, query token + Range206 octets exacts, sans token401 et token invalide401, /test404 ; suppression du fichier personnel de test |

Le cache audio global existant peut servir un fichier à plusieurs utilisateurs authentifiés :
ne pas transformer ce test en exigence de fichier privé ni ouvrir le lot3. Aucun audio réel
utilisé. Les essais locaux du lot1 continuent de couvrir les signatures invalides et bypass ;
le test isolé ne remplace pas une validation des logs Caddy ou des clients mobiles déployés.

Le runner n'annonce passed qu'après tous ces contrôles, sans skipper le SQL ou l'expiration.
L'exécution réelle a reproduit un JWT ES256 émis environ une seconde dans le futur :
401 immédiat puis 200 avec le même token après deux secondes. Le runner attend au plus
deux secondes de décalage `iat`/`nbf` (plus une petite marge), après login et refresh ;
un décalage supérieur arrête le test. Ces claims non vérifiés servent uniquement à temporiser,
la vérification cryptographique et temporelle du backend reste inchangée.
L'attente naturelle est bornée (360s par défaut, petites pauses, sans afficher le JWT) ; un
JWT1h provoque un arrêt, pas une validation d'expiration inventée. Prévoir 10–15min hors
création/initialisation, sans interruption de l'API production. Vérifier les horloges si la
modification SQL n'est pas postérieure au token bootstrap ; ne pas masquer un décalage.

## Nettoyage borné et gestion d'échec

Le runner supprime les playlists/files utilisés pour son CRUD ; sessions/historique et
marqueurs synthétiques peuvent rester pour diagnostic. Il tente aussi le logout en cas
d'échec, mais seul le contrôle explicite logout204/refresh refusé constitue une preuve.
Si l'instrumentation SQL ne peut pas être retirée, arrêter le backend de test et signaler
le schéma/trigger du run ; aucun retry aveugle ou suppression globale.

Après conservation des seuls statuts/empreintes expurgés et **autorisation de nettoyage** :

1. Vérifier le label exact `aura-sec0023-isolated`, puis `docker compose -f <compose-rendu> down`
   dans ce seul projet. Les tmpfs disparaissent ; pas de `down -v`, prune ou commande globale.
2. Projet Supabase dédié : vérifier son ref contre le manifeste/denylist et supprimer ce
   **seul projet** via dashboard, après décision explicite. Ne supprimer ni organisation ni
   autre projet ; pas de reset/dump/restauration. Cette suppression évite de laisser des
   profils AURA orphelins en supprimant seulement les comptes Auth.
3. Supprimer uniquement le dossier local de run vérifié sous son parent protégé (PowerShell
   `Remove-Item -LiteralPath` après contrôle de chemin résolu). Ne pas effacer de parent calculé
   ou les fichiers d'un autre run. Les mots de passe et la clé TLS test ne sont pas archivés.

Un échec conserve NO-GO production. Aucune mise à jour client/version, migration production,
redémarrage VPS, réactivation dev/recovery/backfill ou push n'est inclus.

## Validation de la préparation initiale (historique)

Exécuté le 2026-10-18, avec seulement des identités synthétiques :

| Commande / périmètre | Résultat |
|---|---|
| `server/.venv/Scripts/python.exe -B -m pytest -p no:cacheprovider server/tests/test_sec0023_preparation.py server/tests/test_playlist_rest_contract.py server/tests/test_sec002_operational_tools.py -q --tb=short` | 38 réussites ; pull positif refuse aussi vide/fuite/donnée ancienne/pagination, garde-fous cible SQL/TLS et comptes partiels |
| `server/.venv/Scripts/python.exe -B -m pytest -p no:cacheprovider server/ops_tests/test_sec002_operational.py server/ops_tests/test_sec0023_preparation.py -q --tb=short` | 27 réussites sur PG16.15 local jetable sans réseau/port ; schéma/migration/fixtures, refus de réexécution, trigger privé invoker exécuté avec authenticated et rollback, Compose rendu vérifié |
| Image locale `sha256:5c541f103b62672bc5551aa093f840c878c7129797665ed63d2f0db5a874e16e`, Python3.12/Linux amd64, `python -B -m pytest -p no:cacheprovider tests -q --tb=short` | 230 réussites ; réseau none, paramètres Supabase synthétiques, app/tests/scripts/SQL/templates montés read-only, aucun fichier d'environnement réel |
| Trois CLI lancés sous `python -B -O` | Tous refusent avant configuration/réseau, statut2 vérifié via subprocess |
| Empreintes des handlers métier, SyncService et migration SEC-002.2 | Identiques à la livraison revue ; aucun changement de correctif/migration/client |

Aucun test ignoré aux passages finaux. Avertissement Starlette/httpx préexistant sans échec.
Un premier essai Windows confiné a donné 25 réussites et 13 erreurs de création du dossier
temporaire pytest, sans échec d'assertion métier. Après exécution avec accès au temp local,
un test a révélé un contrôle de nom de pooler trop restrictif ; corrigé, puis 38 réussites.
La journalisation globale est préservée par le préflight de provisioning, vérifié par test.
Pas de nouveaux tests clients : seuls outillage/préparation modifiés ; les résultats Desktop
SEC-002.2 restent historiques, pas une nouvelle exécution SEC-002.3.

Lors de la préparation initiale, les scénarios Supabase réels de la matrice étaient **non exécutés**, notamment le
préflight psycopg, la concurrence HTTP/SDK réelle, refresh/expiration/logout et streaming
avec JWT réels. Aucun backend connecté à un projet réel n'a été démarré. PostgreSQL local
avec claims synthétiques ne prouve ni Auth ni PostgREST ni les politiques d'un projet hébergé.
À ce stade initial, les packages tools/driver n'étaient pas installés dans un venv dédié et la
capacité Free n'était pas vérifiée. Le driver est désormais installé et le SQL de test appliqué,
comme détaillé en tête du document ; les contrôles JWT réels restent à effectuer.
**Préparation locale validée ; NO-GO production inchangé.**

## Sources de référence

- [Sécurité Data API : schémas, grants et RLS](https://supabase.com/docs/guides/api/securing-your-api).
- [Signatures JWT et JWKS](https://supabase.com/docs/guides/auth/signing-keys).
- [Sessions, refresh et expiration](https://supabase.com/docs/guides/auth/sessions).
