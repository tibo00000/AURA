# SEC-002.5 — Une sauvegarde vérifiée pour récupérer AURA

## Décision et périmètre

Ce document complète [SEC-002.4](security-lot2-deployment.md) et remplace le protocole M2
B0/B1 décrit précédemment. L'opérateur indique deux utilisateurs et **accepte explicitement
la perte des modifications entre la sauvegarde vérifiée et une éventuelle restauration**.
Il ne demande plus RPO0. Cela n'autorise ni export privé, ni migration, ni modification VPS.

**Une seule sauvegarde S, récente et réellement restaurée, suffit pour M2.** Aucun second
dump ni seconde restauration complète n'est requis pour ouvrir la fenêtre. Le même S reste
le point de récupération tant que ses fichiers et sa clé sont disponibles. En cas de nouvel
export volontaire, ce nouveau point devra être restauré avant de remplacer S ; ce n'est pas
une étape obligatoire. L'image backend et la migration SEC-002.4 restent figées.

Dépendances : runbooks lot1, SEC-002.1/SEC-002.3/SEC-002.4 et documentation PostgreSQL17.
M2 prouve la récupération des **données AURA**, pas une reconstruction du service Supabase.

## Contenu d'une sauvegarde

| Inclus dans le jeu S | Raison |
|---|---|
| Schéma `public` complet : toutes tables et lignes, catalogue compris | Profils, préférences, playlists/items, likes, lecture, historique, sessions d'écoute/events, mappings, jobs et marqueurs de synchronisation selon inventaire réel ; ne pas choisir uniquement deux UUID et oublier leurs relations |
| Schéma `auth` complet | Conserver les UUID de `auth.users`, `auth.identities`, métadonnées/hashes d'authentification, liens fournisseur, MFA et autres tables Auth existantes ; éviter une sélection manuelle fragile parmi les27tables |
| `supabase_migrations` | Conserver l'historique au snapshot S, antérieur à l'application ultérieure du lot2 |
| DDL des schémas sélectionnés | Types, séquences, PK/FK/index/contraintes différables et validation, triggers, fonctions, RLS/policies, propriétaires, ACL et default ACL |
| Dépendances nécessaires | Fonctions Auth dont `auth.uid()`, rôles nommés dans propriétaires/ACL/policies, versions et fonctions d'extensions effectivement référencées ; support local pgcrypto1.3 et uuid-ossp1.1 dans `extensions` |
| Manifest et preuve chiffrés | Heure du snapshot, périmètre exact, empreintes/counts des tables au même snapshot, versions, droits nécessaires, hash du dump et résultats de restauration |

Les trois schémas représentent48tables selon les catalogues du2026-10-18
(public20, auth27, migrations1). Le nombre réel sera vérifié lors de l'export autorisé.
Deux utilisateurs humains ne justifient pas de supprimer un éventuel compte technique/test :
toutes les identités présentes et leurs UUID sont conservés sans publication de leurs valeurs.

**Exclus** : données/binaires Storage, volume audio et OTA du VPS, schémas Realtime/GraphQL/Vault,
publication/réplication/WAL/PITR, télémétrie, mots de passe des rôles PostgreSQL, clés privées
JWT/API, paramètres OAuth/SMTP/DNS et secrets de la plateforme. Aucune copie de clé Vault
n'est exigée sans dépendance AURA démontrée. Les volumes et la configuration production sont
préservés pendant la migration ; cette sauvegarde n'est pas un secours contre leur destruction.

Le catalogue précédent n'a pas identifié de dépendance public/auth hors ces schémas et
pg_catalog. Ce constat n'est pas une preuve contre du SQL dynamique. La restauration réelle
dans une base vide vérifiera la fermeture des dépendances. Si un objet indispensable manque,
ajouter seulement cette dépendance après qualification et accord sur tout élargissement
privé ; ne pas déclencher une reconstruction de toute l'infrastructure Supabase.

## Identités et rôles : ce que la répétition démontre

Les lignes Auth et les FK `auth.users.id → profiles.id → données privées` sont restaurées
sans changement d'UUID. Ne pas recréer deux utilisateurs via Admin Auth avec de nouveaux UUID.
Les données des identités, MFA et hashes restent strictement privées dans le jeu chiffré.
Si une donnée chiffrée dépend d'une clé externe, l'égalité SQL ne prouve pas son déchiffrement :
qualifier uniquement ce cas réel et sa conséquence (par exemple une réinscription MFA), sans
extraire les clés internes par défaut ni promettre une reprise Auth complète non testée.

La production conserve ses rôles, ses services Auth/PostgREST et leurs réglages. M2 n'exige
plus `pg_dumpall` ni la réinstallation des adhésions globales internes. Conserver les attributs
et droits utiles dans le manifest, sans mot de passe. Dans la copie locale, créer uniquement
des rôles de substitution pour résoudre propriétaires/ACL/policies : NOLOGIN, NOSUPERUSER,
sans CREATEROLE/CREATEDB ; BYPASSRLS conforme au rôle applicatif, jamais pour anon/authenticated.
Le grantor des adhésions internes et les connexions du pooler ne sont pas réinstallés.
Cette différence est déclarée et ne modifie aucun rôle production. Les propriétaires et
ACL des objets applicatifs, ainsi que les permissions/RLS effectives des clients, sont vérifiés.
La comparaison du reçu porte sur ces permissions et dépendances applicatives, avec adaptation
locale déclarée ; elle ne certifie pas l'égalité des attributs de tout le cluster Supabase.

Les tables Auth sont comparées comme données SQL. Aucun service Auth/SMTP n'est lancé sur
les comptes privés copiés, aucun mot de passe d'utilisateur réel n'est essayé. La répétition
ne promet pas la validité des sessions après une reprise sur un autre projet/issuer/clé.
Une reconnexion, voire une récupération de compte fournisseur/MFA, pourra être nécessaire
dans ce scénario ; la reprise sur le projet existant préserve ses paramètres. Les tests Auth
et streaming dédiés du runbook M7 restent nécessaires après déploiement.

## Procédure unique M2-S

1. **Préparer localement** le dossier protégé, age et les outils PG17, la connexion existante
   TLS verify-full et le CA. Vérifier le ref production `neosgkuccazvgkzwzjzh`, DB postgres,
   SELECT complet/bypass RLS, périmètre, espace et clé disponible. Pas de reset de mot de passe,
   création de rôle ou installation sur le VPS. Les outils VPS16.15 ne sont pas utilisés.
2. **Fixer T**, instantané cohérent PostgreSQL REPEATABLE READ READ ONLY. Inscrire l'heure
   serveur en UTC et Europe/Paris, le snapshot-id, début/fin export et backup-id.
   Le reçu M2-S ci-dessous donne le point effectivement créé ; ne pas inventer un point plus récent.
   Prévoir l'export et sa restauration juste avant la fenêtre, idéalement dans la même journée.
3. **Exporter une fois** les trois schémas au snapshot S et chiffrer immédiatement en age.
   Collecter la preuve au même snapshot, pas un comptage source live ultérieur.
   Garder erreurs et empreintes détaillées privées. Flux binaires depuis le coordinateur,
   jamais un pipeline texte PowerShell. Attendre les retours0 de pg_dump et age avant
   de sceller le jeu, fsync et SHA256 ; un `.partial` n'est pas une sauvegarde validée.
4. **Restaurer les octets exacts** de S dans un nouveau conteneur PG17.6 local, sans réseau,
   ports ou volumes production/test partagés. Déchiffrer entièrement et contrôler age avant
   toute exécution SQL. Initialiser seulement les rôles et extensions nécessaires, base vide.
   `pg_restore --exit-on-error --single-transaction`, sans supprimer OWNER/ACL ou contraintes
   pour faire passer un échec. Conserver le schéma public initial vide et ses privilèges de
   base initdb ; omettre uniquement son entrée CREATE SCHEMA redondante dans la liste TOC.
   Conserver toutes les entrées OWNER/ACL/données et comparer les ACL effectives exactement.
   Avant toute lecture privée, reproduire encodage, fournisseur/locale et version réelle de
   collation dans une base locale vide. Ne jamais forcer COLLATION_VERSION pour masquer un écart.
   Pour les colonnes vivantes, comparer leur ordre et tous leurs attributs ; les trous de
   numéros physiques `attnum` laissés par d'anciennes suppressions ne sont pas recréés par pg_dump.
5. **Vérifier** ensemble des tables, counts et empreintes de toutes leurs lignes, UUID/liens
   utilisateurs/identités/profils, FK/contraintes/index, déferrabilité, RLS/policies, droits et
   séquences. Vérifier propriétaire A et B, refus sans identité/tiers, avec fixtures synthétiques
   locales après preuve d'égalité. Déclarer les éventuelles différences de rôles internes.
6. **Émettre un reçu expurgé**, lié au SHA256 exact de S : heure T, source/cible, outils,
   durée réelle, périmètre et égalités. Aucun identifiant utilisateur, token, ligne privée,
   hash de ligne ou stderr brut dans les sorties. Nettoyer le conteneur/tmpfs après contrôle.
7. **Avant maintenance**, afficher l'âge de S et son heure T. Si la fenêtre est reportée au
   lendemain ou si des données à protéger ont changé, proposer de conserver S avec le risque
   accepté ou de le remplacer ; ne pas imposer automatiquement un deuxième dump.
   M2 passe GO seulement après restauration réelle de S. Puis demander les autorisations
   de maintenance/migration/déploiement ; aucune n'est acquise ici.

Commandes coeur du dump et de la copie locale, adaptées par le coordinateur après autorisation :

```text
pg_dump --no-password --format=custom --snapshot=<S> --strict-names
  --schema=public --schema=auth --schema=supabase_migrations
  --no-publications --no-subscriptions
age --recipient <destinataire-public-validé> --output <dossier-autorisé>/aura.dump.age.partial
pg_restore -h /tmp --exit-on-error --single-transaction
  -U sec0025_restore_admin --dbname=aura_recovery <archive-déjà-authentifiée-en-tmpfs>
```

Les dépendances d'extensions situées hors de ces schémas sont initialisées dans la copie
avec leurs versions validées ; elles ne sont pas garanties par les filtres pg_dump.
Le helper `server/ops/sec0025/application_backup.py` définit le périmètre et le bootstrap
local sans connexion ni mode d'export. Le coordinateur `server/ops/sec0025/run_recovery.py`
effectue ces contrôles, l'export binaire age et la restauration privée après autorisation.
Son mode par défaut reste hors réseau et ne lit aucun credential.

## Heure T, perte acceptée et reprise

La règle est **RPO non nul accepté**. Une restauration de S peut perdre **tout changement
postérieur à T**, et pas seulement les écritures pendant la maintenance : nouvelles playlists,
ajouts/retraits/ordre, likes, paramètres, progression/historique/statistiques, nouveaux liens
catalogue/jobs, marqueurs sync et modifications/suppressions correspondantes. Des créations
de comptes, changements d'identités/mots de passe/MFA ou sessions Auth postérieurs à T
peuvent également être perdus ou devenir invalides. Des états antérieurs supprimés après T
peuvent réapparaître. Les marqueurs/outbox peuvent demander une resynchronisation.

Les écritures directes Data API/Auth ne sont pas bloquées par Caddy. Cette réalité ne bloque
plus M2 au titre de RPO0 : l'opérateur accepte la perte S→restauration. Aucun gel global ni
révocation de privilège supplémentaire n'est requis dans ce protocole. Présenter la durée
effective de cet intervalle si une reprise devient nécessaire.

Le jeu S ne doit **jamais** être réinjecté automatiquement en production avec DROP/CASCADE,
TRUNCATE ou une migration inverse improvisée. La migration lot2 reste transactionnelle avec
contrôles d'intégrité du runbook M4/M5. Si un gate critique échoue : maintenance maintenue,
diagnostic, proposition de correction ou récupération nommée, puis décision humaine.
Toute restauration réelle production est une opération distincte à autoriser, avec cible,
schéma compatible et tests définis. Ne jamais exposer le backend vulnérable ou incompatible.

## Stockage et protections essentielles

Destination proposée hors dépôt : `C:\Users\thiba\AURA-private-recovery\SEC-002.5\<backup-id>`,
ACL opérateur/SYSTEM, pas de synchronisation cloud. Un jeu S peut contenir plusieurs composants
chiffrés (dump et manifest/preuve) ; ils appartiennent au **même point T**, sans B0/B1.
Rétention7jours après export, suppression des chemins exacts inventoriés à échéance.
Le conteneur privé et son tmpfs sont détruits à la fin de la vérification, au plus24h.

Clé age dédiée protégée hors dépôt, secours indépendant sous contrôle de l'opérateur ;
jamais dans les rapports. Pas de secret demandé dans le chat : saisie locale masquée ou
fichiers protégés pour le mot de passe DB existant/CA. Pas de copie de données dans le projet
synthétique distant SEC-002.3. Une seule copie chiffrée hors VPS suffit ici ; la perte conjointe
du poste et de la clé reste un risque résiduel, sans multiplication imposée des destinations.

Données déchiffrées seulement dans tmpfs et buffers privés, limites mémoire/swap appliquées.
La VM Docker dispose de swap ; un tmpfs seul ne suffit pas. Docker doit prendre en charge
`--memory=4g --memory-swap=4g` ; vérifier les limites effectives cgroupv1 ou v2 et la protection
du disque hôte/Docker contre le paging. L'état BitLocker n'a pas été vérifié faute de droits
administrateur ; le relevé fourni ensuite par l'opérateur confirme C FullyEncrypted/On/100%.
Le disque Docker sur C sans redirection est vérifié. Ce contrôle est unique.
Un fichier clair persistant ou des logs bruts ne sont pas une solution de secours.

## Résultats et autorisations distinctes

Les résultats effectivement exécutés et leurs limites sont consignés dans
`scratch/sec0025/preparation-simplified-report.json`. Les essais antérieurs de réinstallation
globale des rôles restent dans le rapport historique ; ils ne sont plus un gate du périmètre
applicatif et ne sont pas présentés comme réussis.

**Autorisation M2-S reçue explicitement le2026-10-18** : lecture/export de public/auth/
supabase_migrations et métadonnées nécessaires sur `neosgkuccazvgkzwzjzh`, snapshot cohérent,
chiffrement age immédiat, stockage/ACL/rétention7jours ci-dessus, restauration locale isolée
et nettoyage sous24h. La perte après T est acceptée. Aucun M3/M4/VPS/déploiement/réouverture,
restauration production ou changement d'un secret/rôle existant n'est autorisé.

**Préflight local — état du2026-10-18** : arrêt initial20:19:44+02:00 sur Get-BitLockerVolume
accès refusé en session non administrateur. L'opérateur a ensuite fourni le relevé admin C:
FullyEncrypted/On/100%. Le disque Docker `C:\Users\thiba\AppData\Local\Docker\wsl\data\ext4.vhdx`
et tous ses parents ont été vérifiés sans reparse point ; il réside donc sur ce volume chiffré.
Un conteneur vide temporaire, supprimé automatiquement, confirme les limites effectives cgroupv1 :
mémoire4294967296octets, mémoire+swap4294967296octets, donc aucun swap supplémentaire autorisé.

L'opérateur a terminé la saisie locale dans `m2s-20261018T194030Z-79ed2b89` : fichiers DPAPI,
ACL opérateur/SYSTEM, CA public et secours age relu/attesté disponible indépendamment dans
son gestionnaire. Ce secours est attesté, sans inspection du coffre par Codex.
TLS verify-full est validé par libpq Windows et le client Docker PG17.6, DB postgres/rôle postgres.
SELECT complet et absence de RLS active pour l'exporteur vérifiés sur les48tables.
Le pooler ignore les options de démarrage read-only : le coordinateur impose et vérifie
`BEGIN READ ONLY` explicitement (psycopg `conn.read_only=True`), sans changer le serveur.
La locale source ICU en-US/en_US.UTF-8/version153.120 et sa version réelle ont été reproduites
dans une base vide locale avant lecture privée. Voir le rapport final ci-dessous.

Préparation locale interactive, **sans réseau ni export**, via :

```powershell
powershell.exe -NoProfile -STA -File "C:\Users\thiba\Desktop\AURA-workspace\server\ops\sec0025\Set-RecoveryAccess.ps1"
```

Dans le projet production, télécharger le certificat CA public depuis Database Settings ;
relever uniquement le Host de Connect → Session pooler (port5432), ou laisser vide pour le
host direct connu si accessible en IPv6. Le script fixe DB/projet/utilisateur/TLS verify-full,
saisit le mot de passe deux fois de manière masquée et ne réinitialise aucun secret Supabase.
Il crée une identité age dédiée en mémoire, propose sa copie privée vers le gestionnaire,
puis exige relecture masquée et confirmation que le coffre reste accessible sur un autre appareil.
La copie utilise les formats Windows désactivant historique et cloud pour cet élément ; elle
est retirée du presse-papiers à la fermeture si elle y est encore présente. Ne jamais coller
ces secrets dans le chat, une commande ou un rapport. Aucun secret n'est imprimé.

Le script crée un sous-dossier m2s horodaté/nonce dans le stockage autorisé, ACL opérateur/SYSTEM,
connexion et identité age protégées DPAPI CurrentUser, CA public et manifest sans secret.
Les écritures CreateNew refusent l'écrasement ; aucun fichier privé en clair. Le secours du
manager repose sur la relecture et l'attestation de l'opérateur, pas une inspection du coffre.
Syntaxe, binaires age, aller-retour DPAPI, ACL, refus d'écrasement, nettoyage synthétique,
génération éphémère, parseur CA et formats de presse-papiers testés localement. La saisie réelle
a été effectuée par l'opérateur. L'outil de saisie seul ne teste pas TLS/droits et n'exporte rien ;
les contrôles suivants ont été exécutés par le coordinateur, sous l'autorisation M2-S reçue.

## M2-S exécuté le2026-10-18

**GO M2 — récupération SQL uniquement.** Jeu unique S :

- Dossier protégé : `C:\Users\thiba\AURA-private-recovery\SEC-002.5\m2s-20261018T194030Z-79ed2b89`.
- T, horodatage serveur de l'export du snapshot : `2026-10-18T20:06:43.747899Z`
  (`22:06:43.747899+02:00`, Paris). L'instantané REPEATABLE READ est déjà acquis durant le
  préflight de cette même transaction, avant cet horodatage ; son instant exact d'acquisition
  n'a pas été journalisé. T est un repère d'export, pas une preuve d'inclusion des écritures
  jusqu'à cette microseconde. Une reprise perd les changements depuis l'acquisition du snapshot.
- Dump `aura.dump.age`, SHA256 `1bf2bc8f55d213592a5b2e5245314f7e9959513802ad4e20e7d5c9eae7d12eb5`.
- Preuve `evidence.json.age`, SHA256 `07fbd8103346ba88a8e4d5b2271577134639975f29e94008505caa17a08eb6dc`.
- Rétention : supprimer le jeu au plus tard le `2026-10-25T20:06:43.747899Z`
  (`21:06:43.747899+01:00`, Paris, après changement d'heure). Pas de tâche automatique installée.
- Restauration réelle terminée :48tables comparées intégralement au snapshot par counts/SHA256
  privés, identités/UUID conservés ;16 catégories de contrôles toutes conformes,42FK et1séquence
  vérifiées. Schémas, colonnes/ordre, contraintes, index, propriétaires, ACL/default ACL,
  fonctions, triggers, politiques RLS, rôles clients et extensions conformes au périmètre.
- Sondes avec identités synthétiques uniquement dans la copie locale : visibilité propriétaire,
  absence de playlist étrangère et refus anonyme réussis ; rollback puis comparaison de toutes
  les données identique. Aucun service Auth n'a été lancé sur les données réelles copiées.
- Conteneurs de restauration et tmpfs supprimés immédiatement ; aucun conteneur M2-S/synthétique
  restant. Les12fichiers du dossier sont limités à opérateur/SYSTEM, sans reparse point ; aucune
  archive `.partial`. Empreintes dump/preuve revérifiées après nettoyage.

Deux arrêts initiaux avant snapshot/export : copie CA Docker sur rootfs readonly, puis locale
ICU non prise en charge par le premier coordinateur. Corrigés et vérifiés sans affaiblir les
protections. Le premier restore réel a terminé SQL/données mais arrêté le gate sur les seuls
trous physiques attnum. Reproduction synthétique, comparaison de l'ordre logique corrigée,
puis nouvelle restauration **du même S**, sans nouvelle connexion source ni second dump : succès.
Les reçus et diagnostics chiffrés antérieurs sont conservés, jamais remplacés par un faux succès.

Vérification finale ciblée : `server/.venv/Scripts/python.exe -B -m pytest -p no:cacheprovider
server/ops_tests/test_sec0025_recovery.py -q --tb=short` : **26pass en31.30s**. Aucun test ignoré.
Rapport expurgé détaillé : `scratch/sec0025/m2-s-final-report.md`, reçu JSON adjacent.
Les36artefacts figés SEC0024 sont inchangés. Aucune écriture production/VPS ni migration.

**Après reçu M2 conforme**, demander séparément : transfert/import des artefacts figés,
maintenance/arrêt API, application de
`20261017200822_secure_user_data_isolation.sql` via son wrapper d'intégrité approuvé,
redéploiement de l'image figée et tests M7, puis réouverture après tous les contrôles critiques.
Le détail M0–M8 et les protections lot1 sont conservés ; pas de nouvelle sauvegarde B1 imposée.
Aucun changement/version/push/dev/recovery/backfill n'est inclus.

**État historique à la fin de M2 : gate levé, M3–M8 alors non autorisés.** La restauration démontre
le périmètre SQL existant, pas un service Supabase complet ni la conformité lot2 déjà déployée.
La perte de changements après snapshot, les exclusions et l'écart entre acquisition et repère T
restent explicites. La fenêtre autorisée ultérieure et son succès sont consignés dans
`security-lot2-deployment.md`, section SEC-002.4-RUN. Le jeu S demeure antérieur au nouveau
schéma ; une éventuelle récupération production exigera une procédure compatible et une
autorisation distinctes. Aucun nouvel export ni restauration production effectué pendant la fenêtre.

## Sources

- [PostgreSQL17 pg_dump](https://www.postgresql.org/docs/17/app-pgdump.html) : snapshot cohérent, filtres de schéma et dépendances non incluses automatiquement.
- [PostgreSQL17 pg_restore](https://www.postgresql.org/docs/17/app-pgrestore.html) : erreurs, propriétaires et restauration en transaction.
- [Supabase restauration](https://supabase.com/docs/guides/self-hosting/restore-from-platform) : comptes Auth préservés en base, clés/réglages externes et limites des sessions.
