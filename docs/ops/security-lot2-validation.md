# SEC-002.1 / SEC-002.2 — Validation opérationnelle et cohérence REST/RPC

## Décision et périmètre

SEC-002.4 : le candidat autonome, les contrôles récents en lecture seule et les commandes
finales M0–M8 sont dans `docs/ops/security-lot2-deployment.md`. Ce runbook final précise les
gates non levés et prévaut sur les propositions historiques ci-dessous. Aucune autorisation
de sauvegarde/export privé, transfert, migration, déploiement ou réouverture n'est acquise.

SEC-002.3 : revue indépendante SEC-002.2 favorable selon l'utilisateur. La préparation de
l'intégration réelle est détaillée dans `docs/ops/security-lot2-isolated-integration.md`.
La préparation seule n'autorisait aucune écriture. Après identification du projet test
`nknbuhccnvvhrghvhmll` et « go » explicite, schéma/migration/fixtures y sont appliqués et
vérifiés. Après fourniture des accès, deux comptes synthétiques créés et chaîne intégrée
réelle réussie : 18 groupes incluant verrou/atomicité, pull positif, streaming, expiration401
et logout/refresh refusé ; 233 tests backend et 22 ciblés réussis. Backend local retiré,
instrumentation SQL retirée et vérifiée ; détails et limites dans le runbook SEC-002.3.
Aucune écriture production ni opération VPS autorisée ; détails dans le runbook SEC-002.3.

**SEC-002.2 : corrections et validation locales terminées, revue indépendante favorable.**
GO technique pour préparer la validation intégrée après identification d'un environnement
isolé et autorisation explicite de ses écritures. **NO-GO migration/déploiement production**
avant cette validation réelle et les gates M0–M8. Aucun accès distant effectué pour SEC-002.2.
L'inventaire distant en lecture seule de SEC-002.1 reste une observation historique, pas une
preuve de l'état actuel. Aucune migration, session réelle ou opération VPS dans ces travaux.

## SEC-002.2 — Correctif local et preuves avant/après

Les handlers ajout/retrait/PATCH appellent désormais des actions REST distinctes de
`aura_mutate_playlist_items`, toujours SECURITY INVOKER. `auth.uid()` et RLS déterminent le
propriétaire ; le payload ne peut pas le remplacer. La sélection propriétaire préalable
conserve les 404. La RPC reprend elle-même le contrôle propriétaire sous `FOR UPDATE`.
Mutation enfant, mise à jour du parent et résultat sont dans une seule transaction PostgreSQL.

- `rest_insert` : INSERT simple sans décalage ; collision ID/position = 500 générique et rollback.
- `rest_delete_track` : toutes les occurrences de la piste, sans compaction ; liste vide si absente.
- `rest_patch` : nom/cover/épingle, valeurs null ignorées comme avant ; false conservé.
- Après verrou, timestamp = `greatest(clock_timestamp(), parent.updated_at + interval '1 microsecond')`.
  Même calcul sur les actions items batch existantes ; permutations, conflits et déduplication
  conservés. Le service batch et ses marqueurs ne sont pas modifiés par SEC-002.2.

Les nouvelles assertions sur le code initial échouent **5 fois** : recul du timestamp sur
ajout, retrait et PATCH ; insertion et suppression conservées malgré un 500 du touch parent.
Le PATCH est ainsi confirmé par reproduction, et non par simple hypothèse. Son échec SQL
était déjà atomique avant correction (sixième cas vert). Après correction : **24 tests
PostgreSQL réussis**, couvrant aussi attente réellement observée sur verrou, erreur SQL
intermédiaire/rollback, positions occupées, permutation, suppression multiple, collisions A/B,
payload propriétaire usurpé, HTTP réel synthétique et timestamp précédent dans le futur.

Autres exécutions : **211 tests backend Python 3.12/Linux amd64**, **97 assertions RLS +
3 contrôles préflight/rollback**, **9 tests Desktop** (outbox, playlists, FK Room), sans
échec ni test ignoré aux exécutions finales. Les 3 cas désélectionnés du run rouge sont
exécutés ensuite dans la suite PostgreSQL complète. Avertissements de dépréciation sans échec.
Les commandes et sorties sont livrées dans `security-lot2.2-tests.md` et les preuves associées.

Le runner intégré vérifie d'abord l'OpenAPI de la cible, avant login/écriture : routes et
schémas requis. Lecture par **GET /me/playlists**, recherche de la playlist propre et absence
de celle de B ; aucun GET par ID inventé. Le pull utilise le vrai `sync_token.value` du bootstrap.
Cinq tests hors ligne le confrontent à l'OpenAPI et aux modèles réellement chargés par FastAPI.
**Aucune exécution intégrée réelle effectuée** : PostgreSQL local emploie des claims synthétiques
et un adaptateur SDK SQL, sans Auth/PostgREST réels. PostgreSQL testé : 16.15, pas le 17.6 distant.
Android/Web et sessions UI clientes réelles non exécutés.

La garantie atomique concerne les écritures SQL de ces appels ; une perte réseau après COMMIT
peut toujours laisser une issue inconnue au client. Aucun exactly-once global ajouté aux batches.
La progression couvre ces actions REST/RPC, pas des écritures SQL arbitraires hors de ce chemin.
La migration `20261017200822_secure_user_data_isolation.sql` n'ayant pas été appliquée,
son candidat local est amendé : **faire revoir et figer sa nouvelle empreinte**, puis préparer
l'image backend correspondante. Ne pas réutiliser un ancien artefact lot2 avec ce nouveau SQL.

## Historique SEC-002.1 — Résultats avant correction


`server/ops_tests/test_sec002_operational.py` exécute les vrais handlers REST avec un adaptateur
SDK qui ouvre une transaction PostgreSQL par appel, comme leurs appels PostgREST distincts.
La RPC et les contraintes/RLS s'exécutent réellement sur PostgreSQL 16.15 jetable, sans réseau
ni port publié. Les claims sont synthétiques ; ce diagnostic n'est pas un test Auth/PostgREST.

| Contrôle | Résultat observé | Interprétation |
|---|---|---|
| Ajout REST arrêté avant le touch du parent, puis reorder RPC | La RPC accepte l'ancienne version ; le touch REST fait ensuite reculer `updated_at` | Défaut confirmé, bloque la cohérence de version |
| Retrait REST arrêté avant le touch, puis reorder RPC | Même régression d'horodatage | Même défaut, deuxième parcours confirmé |
| Échec synthétique du touch après insertion REST | Réponse 500, item pourtant enregistré, parent inchangé | Défaut confirmé : deux transactions, reprise ambiguë |
| Position REST déjà occupée | 500, insertion refusée, positions intactes | Comportement préexistant ; pas une nouvelle IDOR ni une régression prouvée de la migration |
| Permutation RPC puis ancien token | Permutation valide, second reorder en conflit | Contrainte différable et verrou parent fonctionnent sur la RPC |
| Inventaire SQL et comparateur hors ligne | Lecture seule, comptes agrégés, schéma initial accepté pour revue ; état déjà migré bloqué | Ne vaut pas validation de la base déployée |

Les **6 diagnostics réussis reproduisent aussi les défauts** ; leur vert n'est pas un GO.
La suite backend avec les nouveaux garde-fous donne **206 réussites, 0 échec, 0 ignoré**
sous Python 3.12/Linux amd64. Deux avertissements Starlette/httpx et métadonnée Pydantic alias.
Le lot 1 seul donne **168 réussites + 12 sous-tests**, sans le lot 2.
Les commandes et sorties exactes sont conservées dans les preuves SEC-002.1.

Correction minimale à étudier dans un changement distinct : faire partager le même verrou
parent et la même transaction aux écritures REST sur les items et à leur `updated_at`,
en préservant l'insert REST sans décalage implicite et le DELETE REST de toutes les occurrences
d'une piste. Ne pas remplacer aveuglément ces routes par la RPC batch : celle-ci insère avec
décalage et supprime un seul item. Pour PATCH métadonnées, qualifier aussi l'horodatage calculé
avant attente de verrou. Ce scénario n'avait pas été exécuté en SEC-002.1 ; il est maintenant
reproduit et corrigé localement en SEC-002.2, comme détaillé ci-dessus. Aucun contrat HTTP modifié.

## Inventaire distant proposé — autorisation préalable obligatoire

### Requêtes exactes et données retournées

| Ordre | Fichier exact | Informations retournées |
|---|---|---|
| I1 | `supabase/sql/sec002_inventory_metadata.sql` | Version PostgreSQL, schémas visibles et GUC PostgREST visible, tables public, colonnes/types/nullabilité, RLS, policies et empreintes de leurs expressions, ACL tables/colonnes, droits effectifs, rôles et appartenances sans mots de passe, fonctions et empreintes/capacités EXECUTE, vues et empreintes/security_invoker, triggers sans arguments, contraintes/colonnes/FK/différabilité, index et dépendances catalogue |
| I2 | `supabase/sql/sec002_inventory_counts.sql` | Seulement comptes globaux : liens session/événement/historique incohérents, marqueurs/propriétaires ou items orphelins, positions dupliquées/négatives, volumes globaux des tables concernées |
| I3 conditionnel | `supabase/sql/sec002_inventory_batches.sql` | Seulement nombre de marqueurs de batch et propriétaires orphelins ; ne pas exécuter si la table est absente |

Chaque fichier impose `BEGIN ... REPEATABLE READ READ ONLY`, statement timeout20s,
lock timeout2s et `ROLLBACK`. Aucun DDL/DML, CALL/DO, copie de ligne, création de rôle ou
appel à une fonction applicative. Les définitions SQL sont hachées côté serveur ; elles
ne sont pas exportées en clair. Aucun UUID de ligne/utilisateur, contenu privé, JWT,
secret, mot de passe, définition de fonction, argument de trigger ou journal brut retourné.
Les noms d'objets et de rôles SQL sont des métadonnées retournées pour l'audit.

I1 est examiné **avant** I2/I3 : présence des colonnes/types, droits SELECT complets et
`executor_rls_active=false` sur toutes les tables comptées. Un compte utilisateur sous RLS
ne fournit pas de comptage global valable. Une table/colonne manquante impose l'arrêt,
pas une correction du schéma ni une requête élargie improvisée. Chaque fichier est son
propre snapshot ; figer les écritures et recompter pendant la maintenance future, car
un inventaire préalable n'est pas une preuve stable jusqu'à la migration.

### Canal et droits requis

Utiliser une connexion SQL existante (MCP SQL du projet sélectionné AURA, ou service psql
protégé), jamais une clé `service_role` HTTP comme substitut à une connexion PostgreSQL.
Métadonnées : lecture des catalogues PostgreSQL usuels, sans `pg_authid`, et fonctions
catalogue de description/empreinte. Comptages : USAGE public et SELECT sur profiles,
playlists, playlist_items, listening_sessions, history_items, playback_events,
processed_operations et processed_batches si présente, avec visibilité complète des lignes
(rôle existant BYPASSRLS/superuser, ou propriétaire non soumis à FORCE RLS).
Le rôle doit être capable de lire toutes ces lignes, mais la transaction doit rester READ ONLY.
Ne créer/modifier aucun rôle, grant, secret ou connexion pour ce contrôle sans nouvelle demande.

Exemple de commande **préparée, non exécutée à distance**, alias SQL à configurer hors conversation :

```powershell
psql "service=aura-sec0021-readonly sslmode=verify-full" -X -qAt -v ON_ERROR_STOP=1 -f supabase/sql/sec002_inventory_metadata.sql
```

Même commande, fichier I2 puis I3 seulement si les gates précédents passent. Sorties JSON
dans un dossier opérateur protégé, pas de chaîne de connexion/mot de passe dans les arguments
ou rapports. L'alias n'est pas créé ici. Les sorties retenues passent par le stockage de preuves.
Aucun `db pull`, `db push`, dump ni advisor distant n'est inclus dans cette autorisation.

### Comparaison aux préconditions de la migration

`server/scripts/check_sec002_inventory.py` compare les JSON hors ligne. Il retourne NO_GO
si un blocage est détecté, sinon MANUAL_REVIEW_REQUIRED, **jamais un GO de déploiement**.

| Précondition de `20261017200822_secure_user_data_isolation.sql` | Critère et arrêt |
|---|---|
| PostgreSQL >=15 | Sinon SET NULL ciblé non compatible : arrêt |
| Tables, colonnes et types attendus | Comparateur sur colonnes utilisées par les contraintes/RPC ; audit complémentaire du schéma complet |
| anon/authenticated non privilégiés | Pas de superuser/BYPASSRLS, membre service_role ou propriétaire ; droits hérités/colonne à examiner |
| Aucun code privilégié exposé non revu | Fonctions public SECURITY DEFINER exécutables et triggers privés privilégiés bloquent ; invoker/callees dynamiques et vues exigent revue |
| Sessions et propriétaires cohérents | Comptes I2/I3 à zéro ; sinon arrêt et décision séparée, aucune réparation |
| PK des marqueurs modifiables | Inspecter PK, uniques globaux supplémentaires, FK et autres dépendances ; aucun DROP CASCADE |
| Contrainte `unique_playlist_position` attendue | Même table/colonnes, pas de collision de nom ; vérifier index/validation ; changer la différabilité ne rend pas les appels REST atomiques |
| Noms nouveaux libres | Pas de RPC/contraintes SEC-002 déjà présentes ; vérifier aussi policies/index optionnels ; migration non destinée à être rejouée arbitrairement |
| Schémas effectivement exposés connus | GUC visible peut être vide/incomplète ; confirmer Data API dans le tableau de bord, ne pas supposer public seul |

Les empreintes ne prouvent pas la sémantique d'une vue/policy/fonction inconnue. Une différence
exige une revue ciblée sur place, puis une autorisation adaptée si une définition doit être lue.
Les dépendances PL/pgSQL/dynamiques ne sont pas toutes traçables dans pg_depend.
Aucune incompatibilité de la base distante n'est affirmée avant son inventaire.

## Résultat de l'inventaire autorisé

L'utilisateur a autorisé uniquement I1 puis I2/I3 conditionnels. Projet AURA sélectionné via
le connecteur Supabase existant ; I1 et I2 exécutés sans modification des requêtes, avec
`transaction_read_only=on`. Les droits SELECT complets et l'absence de filtrage RLS de
l'exécutant ont été vérifiés avant I2. I3 **non exécuté** : processed_batches absent.

- PostgreSQL **17.6**, 20 tables public, RLS activée sur ces tables, 13 policies permissives.
- Aucune fonction ou vue public, aucun trigger applicatif public. Cela ne prouve pas
  l'absence de tout code dans d'autres schémas ; la GUC des schémas PostgREST est NULL
  dans cette connexion et doit être confirmée via configuration Data API.
- anon/authenticated : ni superuser, ni BYPASSRLS, ni membre service_role/propriétaire ;
  aucune appartenance héritée ou ACL colonne retournée pour ces contrôles.
- Contraintes et types requis compatibles : unique_playlist_position attendue présente,
  ancienne PK globale processed_operations(operation_id), aucun FK entrant vers ces marqueurs
  relevé ; noms de la future RPC/FK SEC002 libres. processed_batches sera créée par la migration.
- Événements/session incohérents=0, historique/session incohérent=0, marqueurs orphelins=0,
  playlists orphelines=0, items orphelins=0, positions dupliquées=0 ou négatives=0.
- Volumes agrégés : 3 profils, 4 playlists, 251 items, 9 843 historiques, 0 session/événement,
  21 795 opérations. Aucune ligne ni identité privée exportée.
- Droits CRUD/TRUNCATE anon/authenticated présents sur les tables privées inspectées.
  La migration préparée révoque ces droits puis n'accorde que CRUD à authenticated sous
  garde RLS (aucun droit client jobs). Ne pas présenter TRUNCATE comme exploitable par HTTP
  sans chemin SQL exposé ; aucune tentative destructive ni offensive n'a été faite.
- Comparateur hors ligne : **MANUAL_REVIEW_REQUIRED**, aucun blocage automatique relevé.
  Les droits hérités/ACL colonne et dépendances PK ont été examinés ; policies anciennes
  restent permissives mais la migration ajoute une garde restrictive. Les expressions
  n'ont été exportées que sous forme d'empreinte, pas relues en clair.

Ces constats ne constituent ni une application à blanc de la migration, ni une validation
de son exécution, ni un GO. Refaire les comptes sous maintenance ; confirmer les schémas
exposés, corriger/revoir REST/RPC et réussir la vraie intégration isolée avant activation.
Preuves : deployed-metadata.json, deployed-counts.json et remote-inventory-receipt.json,
conservées dans le dossier de livraison SEC-002.1, sans corps SQL applicatif ni donnée privée.

## Historique SEC-002.1/2.2 — test intégré préparé avant SEC-002.3

Le texte suivant décrit la préparation initiale. La véritable exécution autorisée SEC-002.3
est désormais réussie, comme indiqué en tête et dans son runbook ; les permissions de
production et les autres gates M0–M8 restent distinctes.

`server/scripts/verify_sec002_integrated.py` est un runner préparé. Par défaut il vérifie
uniquement un manifeste protégé, sans réseau. `--execute-isolated` ne devra être utilisé
qu'après autorisation explicite des écritures **dans l'environnement isolé nommé**.
Le runner ne crée ni comptes, ni catalogue, ni migration et n'accepte pas de clé serveur secrète.
Il refuse HTTP, userinfo/query dans les URL, hôtes de production déclarés et comptes identiques.
Ces contrôles ne prouvent pas à eux seuls qu'un projet est isolé : attestation opérateur requise.

### Prérequis à préparer après autorisation distincte

1. Un projet Supabase vide dédié, ou pile locale éphémère complète Auth/PostgREST/PostgreSQL.
   Aucun clone de données privées, aucune liaison au projet/stockage/cookies de production.
   Le dev actuel et ses volumes partagés ne sont jamais utilisés.
2. Schéma du dépôt + migration lot2 revue, appliqués seulement dans ce test isolé autorisé.
   Un artiste, un album et une piste synthétiques selon le vrai schéma catalogue suffisent ;
   aucun provider externe, téléchargement ou fichier audio privé. UUID/IDs de test opaques.
3. Deux comptes Auth A/B confirmés et dédiés, mots de passe protégés, sans données personnelles,
   sans inscription publique ni email réel requis. Comptes déjà créés avant le runner.
4. FastAPI corrigé dans un processus/conteneur séparé, lié uniquement à ce projet et à une
   arborescence temporaire. Debug, recovery et backfill false ; logging du lot1, rétention
   bornée, aucun port public. SDK >=2.33.0. Ne jamais injecter les secrets production.
5. HTTPS pour SUPABASE_URL et issuer réel identique côté FastAPI/SDK/Auth ; ES256/RS256 via
   JWKS ou HS256 avec **secret de ce test uniquement**. Pour une pile localhost, TLS local
   et CA éphémère explicitement approuvée/trustée par le seul processus de test ; aucun
   `verify=False` ni assouplissement de la vérification JWT du lot1.

Manifeste JSON protégé hors Git : purpose=`aura-sec0021-isolated`,
confirmed_empty_isolated_environment=true, production_deny_hosts (API et Supabase prod),
expected_project_host, supabase_url, api_url, anon_key publique, test_track_id et users[2]
avec email/password/expected_id. Ne coller aucune de ces credentials dans la conversation.
Sous Windows, ACL au compte opérateur seulement, ou déchiffrement DPAPI transitoire contrôlé.
Ne pas utiliser les credentials créés pour la validation production du lot1.

```powershell
# Configuration seule, sans connexion ; ne pas substituer de secret en ligne de commande.
server/.venv/Scripts/python.exe -B server/scripts/verify_sec002_integrated.py --manifest <chemin-protege>
# Exécution ultérieure, UNIQUEMENT après autorisation écrite du projet isolé et de ses écritures :
# même commande avec --execute-isolated
```

Le runner couvre login réel A/B et bootstrap (signature acceptée par FastAPI), CRUD propriétaire,
refus REST étrangers, collisions/mélanges batch, sessions/historique/events, accès directs SDK
aux tables RLS, bootstrap/pull simultanés, marqueurs identiques entre comptes/retry et refresh.
Chaque requête FastAPI utilise son client JWT puis PostgREST/PostgreSQL réels. Il ne publie
que les noms des contrôles et leur statut ; tokens en mémoire, exceptions HTTP expurgées,
logout des comptes tenté en sortie. Vérifier explicitement les statuts logout en contrôle
complémentaire si une preuve de déconnexion est requise ; ne pas confondre logout et
révocation instantanée de tous les JWT. Pas de nettoyage général automatique : fixtures
restent dans le projet isolé pour diagnostic puis suppression autorisée du seul environnement.

Compléments obligatoires avant GO : exécuter une vraie contrainte refusée via SDK (parent B),
collisions globales sur chaque entité, doublons/mélanges/échecs intermédiaires et concurrence
REST/RPC après correction. Les diagnostics locaux rendent les interleavings déterministes ;
le test réel contrôle toute la chaîne mais ne garantit pas leur couverture par hasard.
La syntaxe/configuration du runner est testée, **pas son succès sur une vraie pile Supabase**.

## Plan de maintenance SQL puis backend — proposition non autorisée

| Étape | Action future proposée | Critère avant de poursuivre / point d'arrêt |
|---|---|---|
| M0 hors maintenance | Revue indépendante SEC-002.2 ; inventaire autorisé revu ; test intégré isolé réel ; préparer image Linux amd64 avec SDK requis et sources exactes | Tous les contrôles passent, nouvelle empreinte migration/diff/image arrêtée ; sinon aucune fenêtre |
| M1 préparation | Deux SSH indépendants, secours console/solution approuvée ; vérifier lot1 effectivement actif, disque, TLS/JWKS et overrides | Toute dérive/absence de secours : arrêt ; aucune extrapolation depuis le local |
| M2 sauvegarde | Confirmer sauvegarde Supabase/PITR récente et récupération ; sauvegarder schéma public/ACL/fonctions et données dans stockage protégé, empreintes et TOC ; sauvegarder Caddy/config active et identités images | Backup valide et restauration maîtrisée, pas simple fichier déclaré ; aucune donnée privée dans rapport ni copie vers tests publics |
| M3 confinement | Maintenance Caddy conservant santé/statique OTA nécessaires, arrêter uniquement API et vérifier zéro écriture/connections actives de l'ancien backend | Vérification extérieure du mode maintenance, deux SSH encore fonctionnels, dev arrêté ; sinon maintenir confinement |
| M4 SQL | Refaire les comptes/gates sur snapshot stable, appliquer **la seule migration nommée autorisée** avec ON_ERROR_STOP | Une erreur annule la transaction ; ne pas continuer ni réparer automatiquement |
| M5 contrôle SQL | PK/FK validées, policies restrictives/EXECUTE/ACL et invoker conformes, volumes et comptes incohérents inchangés/zéro | Toute divergence : maintien maintenance ; aucune remise publique de l'ancien client service_role |
| M6 backend | Importer/vérifier l'image corrigée puis recréer seulement aura-api, sans build/pull ni dev | Image/sources/SDK exacts, health200, debug/routines false, pas de ports hôte, logs sûrs et rétention |
| M7 avant réouverture | /test404, privé sans token401, marqueurs synthétiques absents des logs sensibles avec trace utile, session dédiée/streaming200-206 selon périmètre séparément autorisé | Aucun test intercompte offensif sur données production ; si KO, rester en maintenance |
| M8 réouverture | Caddy release sécurisé, contrôles HTTPS externes, dev503, ports8000/8001 inaccessibles, deux SSH, écoute/ports Docker absents | Échec : rebasculer en maintenance, prolonger et demander une décision |

Les comptes destinés à des tests offensifs ne sont **jamais créés/utilisés en production**.
Une éventuelle sonde positive production emploie seulement un compte/une fixture déjà dédiés,
avec périmètre d'écriture autorisé séparément. Pas de migration/test secret implicite.

### Protections lot1 impératives

Conserver les deux Compose VPS de base **et** compose.containment.yml **et** compose.release.yml
de `infra/security-lot1/`, dans cet ordre ; la future image lot2 doit remplacer la valeur
AURA_SECURITY_IMAGE par son identité validée sans retirer ces protections. Les Compose/distants
historiques seuls réexposeraient ports/debug/code ancien. Ne lancer aucun profil dev.
Vérifier la commande de logging sécurisée du lot1, json-file max-size10m/max-file3 sur API
et Caddy, redaction token/Authorization/Referer, debug/recovery/backfill false, réseau Caddy
interne, aucune publication8000/8001. Ces propriétés ne sont pas déclarées revérifiées à distance ici.

Sauvegarder le Caddyfile par copie dans un dossier protégé. Installer/restaurer **en place**
par écriture sur le même inode (fsync, hash hôte/bind puis JSON actif exact), comme le runbook
lot1 ; pas de renommage de fichier cassant le bind mount. Recharger seulement après validation
et autorisation opérationnelle. Ne jamais restaurer une configuration réexposant l'ancien backend.
La maintenance et SSH doivent être contrôlés depuis l'extérieur à chaque transition.

### Gestion d'échec et durée

Avant COMMIT SQL : rollback transactionnel seulement ; conserver API arrêtée si le diagnostic
est incertain. Après COMMIT : garder le schéma et la maintenance ; réparer en avant sur un
candidat revu. Une éventuelle restauration est une décision distincte et contrôlée, **pas** un
down SQL improvisé ni un redémarrage public de l'ancien backend. Sauvegarder les preuves
expurgées, indiquer l'étape, demander la décision. Ne jamais réactiver dev/recovery/backfill.

Budget indicatif après prérequis : 5min confinement/arrêt, 5–10min SQL/contrôles, 5–10min
backend/tests, 5min réouverture, soit 20–30min. Ce n'est pas une garantie : volumes, locks,
sauvegarde/restauration et vraie intégration ne sont pas mesurés. Échec => prolongation,
sans réexposition. La préparation et les sauvegardes doivent précéder le décompte de fenêtre.

## Clients et contrats

Aucun client, payload, version, OTA ou schéma Room modifié. Tests pertinents présents :
DesktopCloudSyncOutboxTest, DesktopPlaylistManagerTest, RoomForeignKeyIntegrityTest et tests
partagés playlist/queue ; les tests Android existants ne couvrent pas Auth/RLS batch.
Wrapper du dépôt et JDK21 détectés. Commande ciblée exécutée avec succès :

```powershell
./gradlew.bat :desktopApp:desktopTest --tests "com.aura.music.desktop.domain.DesktopCloudSyncOutboxTest" --tests "com.aura.music.desktop.domain.DesktopPlaylistManagerTest" --tests "com.aura.music.desktop.domain.RoomForeignKeyIntegrityTest" --offline --no-daemon --console=plain
```

**9 tests réussis** (3 par classe), 0 échec, 0 erreur, 0 ignoré, XML frais du 2026-10-17T21:26Z
et tâche desktopTest effectivement exécutée. Ces tests de Room/outbox/playlist ne valident
pas une session Supabase réelle. Dépendances en cache, pas de build global, packaging Release,
installation ou modification client. Aucune validation Android/Web exécutée. Pas de suite
Web Auth/sync identifiée ; cible Web en stand-by dans BUILD.
Prévoir tests UI A/B sur binaire Android/Desktop existant avec le projet isolé, sans forcer
une mise à jour. Le défaut REST/RPC n'autorise pas de changement de contrat implicite.

## Autorisations à obtenir et point d'arrêt

1. **Autorisation reçue et consommée :** I1/I2 sur Supabase AURA production, uniquement
   métadonnées/comptages, aucune écriture ou extraction de définition/ligne privée.
   I3 omise car table absente ; aucune opération distante supplémentaire implicitement autorisée.
2. Séparément : préparation d'un projet/pile Supabase vide, deux comptes et fixtures synthétiques,
   migration/test intégré dans ce seul environnement isolé. Aucune autorisation acquise ici.
3. Après résolution des défauts et revue : autorisation nominative de sauvegarde/maintenance,
   migration production puis redéploiement aura-api et transitions Caddy. Aucune acquise ici.

Le commit lot1 `16332183c7f453c27ce840658f862b6183947208` a été créé sur demande explicite,
sans push. Il exclut tout SEC-002/SEC-002.1 et préserve les fichiers de travail. Les fichiers
partagés ont été indexés depuis leurs copies pré-lot2, vérifiées ; la base pyproject reconstruite
par inversion de la seule ligne SDK est documentée dans la livraison SEC-002.
Les nouveaux travaux restent non commités pour revue. Arrêt avant tout accès/modification
distante non autorisé et avant le lot3.
