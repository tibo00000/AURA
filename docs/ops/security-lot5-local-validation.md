# SEC-005 — Validation locale et réserves opérationnelles

Références : `docs/server/storage.md`, `docs/server/api-contract.md`, findings
originaux6 et8 dans `docs/server/security-and-secrets.md`. Ce lot conserve le
remplacement collaboratif volontaire, sans quota individuel total et sans purge.

## Politique initiale retenue

| Mesure | Valeur | Effet |
| --- | --- | --- |
| Audio reçu | 50Mio =52 428 800 octets | Bloquant413, compteur réel du parseur et seconde vérification du handler |
| Enveloppe HTTP multipart | 51Mio =53 477 376 octets | Bloquant413, compteur réel ; marge pour champs/en-têtes, pas50Mio de corps total |
| Par compte | Deux uploads admis simultanément | Bloquant429 avant lecture du corps ; réservation maintenue jusqu'à fermeture des spools et fin des écritures |
| Activité | Plus de15 admissions/10min | Informatif, jamais un refus429 de débit |
| Octets | Plus de2Gio reçus/24h | Informatif ; buckets minute, marge temporelle<60s ; inclut tentatives reçues même échouées |
| Volume audio | max(10Gio,10% de sa capacité) | Bloquant507 pour admission/copie manquant de marge, pas un quota total par compte |

50Mio représentent environ21min50 d'audio320kb/s, mais seulement4min57 de PCM
CD stéréo16bit/44,1kHz. Ce seuil proposé est retenu pour les uploads usuels de
morceaux compressés ; il peut refuser des WAV/FLAC longs actuellement acceptés.
**La distribution réelle des tailles n'a pas été mesurée dans cette tâche**.
Avant activation, vérifier par comptages/tailles agrégés autorisés si ce seuil
convient ; le modifier ensemble dans Settings/env et le fragment Caddy si nécessaire.
Android lit déjà le fichier entier en mémoire avant envoi : aucune modification
client effectuée et aucune promesse de prise en charge des très gros fichiers.

Deux admissions conviennent au petit groupe tout en autorisant l'envoi depuis
deux appareils du même compte. Le troisième reçoit Retry-After10 et peut être
retenté après libération d'une place. Les alertes n'interrompent pas un usage actif.
Elles sont des compteurs mémoire, pas une comptabilité durable ; un redémarrage
les réinitialise. Le plafond de50Mio est configurable par UPLOAD_MAX_FILE_BYTES,
les deux composantes de marge par UPLOAD_MIN_FREE_BYTES et UPLOAD_MIN_FREE_RATIO.
Les valeurs zéro ne sont utilisées que dans les tests à filesystem tmpfs512Mio ;
cela n'est pas une configuration de production recommandée.

Le reçu expurgé SEC-003.4 déjà présent (`scratch/sec0034/p1-result.json`) indiquait
154 894 188 544 octets de capacité (144,26Gio), dont114 247 168 000 libres (106,40Gio).
La réserve calculée serait14,43Gio. Ce sont des observations historiques, **pas
une preuve d'espace actuellement disponible**. Aucun nouvel accès distant autorisé.

## Réception et stockage

La route d'upload vérifie le même JWT SEC-001 avant de lire le multipart. La
dépendance normale reste vérifiée par FastAPI ; les UUID doivent correspondre.
La préparation de la route respecte les dependency_overrides serveur des tests,
qui ne sont pas pilotables par une requête HTTP. Le parseur Starlette existant
est borné et utilisé une seule fois ; code testé avec FastAPI0.143.3,
Starlette1.8.0, python-multipart0.0.32. Les callbacks et le cache Request._form
constituent un point de compatibilité à retester lors d'une mise à jour de ces
dépendances. Aucune dépendance/version n'a été modifiée par SEC-005.

Spool privé sous SYNC_FILES_DIR/_upload_spool,1Mio maximum en mémoire par fichier,
puis disque. Une réservation initiale de2×taille maximale +1Mio couvre le spool,
le nouveau fichier préparé et les métadonnées. La réserve restante diminue avec
les données déjà allouées ; l'ancien contenu reste dans l'espace effectivement
occupé. Si un hardlink de sauvegarde est indisponible, une copie transitoire est
réservée en plus selon la taille historique réelle. Refus avant écrasement si la
copie ne tient pas. Aucune copie illimitée ne contourne le garde-fou.

La publication du cache natif et les copies de repli consultent le même budget.
Tous les octets existants, cache compris, entrent dans disk_usage ; l'éviction
SEC-003 reste désactivée. Le contrôle est coopératif mono-processus, **pas un quota
filesystem** : yt-dlp/FFmpeg, Docker, logs ou writers administratifs peuvent
consommer indépendamment entre deux contrôles. Un ENOSPC reste possible ; les
erreurs ordinaires gardent les anciens fichiers via rollback. Une interruption
brutale peut laisser un temporaire nommé ; son espace est ensuite compté, sans
nettoyage aveugle ni backfill. Aucun nettoyage de cache n'est réactivé.

La publication garde l'ordre existant piste → job → STORAGE_LOCK ; les commandes
gardent admission de paire → job quand nécessaire, sans attendre un worker sous
ces gardes. Les nouveaux moniteurs de budget sont courts, depuis le chemin disque ;
SQLite n'acquiert pas ces gardes en sens inverse. Pas de seconde tentative native
admise par ce lot.
La réservation d'upload ne garde pas son moniteur pendant une attente réseau.
Les remplacements audio/JSON sont des renommages séparés, avec synchronisation
des fichiers/répertoires sur Linux et rollback des erreurs ordinaires. Ils ne
forment pas une transaction de crash globale. Une défaillance répétée pendant
le rollback lui-même peut exiger une intervention et laisser une sauvegarde privée.

## Historique durable et consultation

SQLite a été choisi : bibliothèque standard Python, volume audio déjà durable,
deux utilisateurs et un seul processus. Une base Supabase supplémentaire aurait
imposé migration, droits Data API/RLS et coordination réseau sans rendre atomique
la publication de fichiers locaux. **Aucune migration Supabase ni modification de
rôle/secret n'est nécessaire.** Le DDL SQLite de la nouvelle table est embarqué
dans replacement_history.py ; il a été exécuté uniquement dans le stockage local
des tests. Une future activation créera un nouveau fichier local dans le volume.

Journal privé `_audit/audio-replacements.sqlite3`, répertoire0700/fichier0600 à
la création, WAL et synchronous=FULL, connexions courtes. Intention avant effet,
ancien/nouveau SHA-256 et acteur UUID vérifié ; aucun token, email, source_hint ou
URL fournisseur. Motif optionnel énuméré, pas de champ de texte libre. Un événement
par tentative interne, y compris échec fournisseur et annulation d'une tentative
engagée. Une requête rejetée avant démarrage n'est pas un remplacement et n'ajoute
pas d'événement ; les tentatives en attente seulement dans PostgreSQL après un
crash avant démarrage peuvent ne pas avoir d'entrée dans le journal.

Succès après cache + lien personnel + propagation des références partagées + SQL
du job confirmés. Une écriture SQL ratée après effet est partielle. Une confirmation
SQLite impossible reste publishing puis indeterminate au prochain processus, sans
succès inventé. Aucun rollback automatique de données ou d'audio partagé, et aucun
ancien audio archivé. Le journal ne garantit pas un commit atomique distribué
entre SQLite, PostgreSQL et le filesystem ; l'état incertain est volontairement visible.
En cas de plusieurs workers, les gardes et compteurs divergeraient et un worker
pourrait considérer une intention de l'autre comme interrompue : **configuration
multi-processus non supportée**, à redessiner avant tout changement de workers.

GET /me/audio-replacements est authentifié et collaboratif. Les membres voient
les acteurs UUID des remplacements partagés, pas les bibliothèques personnelles.
Pagination/filtre dans le contrat API. Les clients actuels n'affichent ni cette
liste ni les nouveaux en-têtes d'alerte ; une interface ultérieure peut les utiliser
sans changer les contrats de streaming/sync. Aucun client n'est modifié ici.

Sauvegarder ce journal avec le volume : SQLite backup ou arrêt/checkpoint avant
copie, jamais seulement le fichier principal d'un WAL en cours. La sauvegarde
Supabase seule ne contient pas ce nouvel historique. Pas d'endpoint de suppression
ou de conservation d'anciens audios, ni fonctionnalité de restauration audio.

## Caddy et éventuelle activation future

`infra/security-lot5/Caddyfile.uploads.snippet` est **un candidat non appliqué**,
à importer dans le site production existant après revue et autorisation distincte.
Il limite seulement les POST fichiers à53 477 376 octets ; il ne touche ni GET/Range,
ni les filtres de logs SEC-001, ni le dev maintenu fermé. La directive request_body
agit lors de la lecture du corps. Ne pas activer request_buffers dans reverse_proxy.
Les fichiers locaux et le reçu précédent ne montrent pas de buffering forcé ni
de limite de corps explicite ; cela ne prouve pas la configuration déployée actuelle.

Adaptation locale réussie avec Caddy2.8.4 (aucun proxy démarré, réseau none).
Le binaire de l'image officielle porte une capacité NET_BIND_SERVICE ; l'exécution
avec toutes les capacités retirées a échoué avant parsing. Le contrôle final
autorise seulement cette capacité, conserve lecture seule/no-new-privileges et
réseau none. Ce n'est ni un test de trafic Caddy→Uvicorn ni une validation distante.

Documentation primaire consultée :
[request_body](https://caddyserver.com/docs/caddyfile/directives/request_body),
[reverse_proxy et request_buffers](https://caddyserver.com/docs/caddyfile/directives/reverse_proxy).

Pour une mise en service ultérieure, demander séparément : contrôles distants en
lecture seule si nécessaires, sauvegarde cohérente du volume, préparation/import
d'un candidat autonome, déploiement backend et éventuelle insertion/reload Caddy.
Conserver HTTPS, logging expurgé, ports privés, workers1, dev/recovery/backfill
désactivés et les six overrides courants. Ne pas réutiliser le Compose de base seul.
Vérifier le volume réellement monté et ses droits, marge/inodes, tailles agrégées,
limite proxy cohérente, persistance SQLite et protocole synthétique de remplacement.
Les anciennes protections SEC-001/002/003 ne sont jamais retirées. Aucun de ces
gestes opérationnels n'est autorisé ou exécuté par cette livraison.

## Preuves locales

Suite finale :354 tests backend Linux/Python3.12,0 échec/ignoré en69,05s ;
152 ciblés avant le dernier correctif, puis contrôle tronqué1/1 et suite entière.
PostgreSQL17.11 :97 assertions SEC-002 +10 SEC-003 et3 gates de rollback,
sans réseau/port et conteneur de test nettoyé. Premier passage ciblé147 dont
un fallback copie régressé puis corrigé ; première relance147/147 et ciblés152/152.
Deux limites étaient rouges sur le snapshot initial et le hardlink déjà vert.
Un contrôle tronqué supplémentaire a révélé un spool non fermé ; rouge avant,
vert après correction, puis suite354 complète. Dépréciation TestClient/httpx
préexistante conservée, aucun test neutralisé. Caddy adapté localement, pas de
test proxy actif ni session Supabase distante. Après consolidation du code d'erreur
historique pour les sorties invalides, trois contrôles ciblés puis354 tests ont
été rejoués sur les77 empreintes app/tests finales. Le dernier passage affiche
également un avertissement Pydantic d'alias token lors du test concurrent SEC-002,
qui réussit ; le vérificateur Auth n'a pas été modifié.

Le rapport et le patch incrémental sont sous scratch/sec005, base constituée par
le snapshot local préexistant et son manifeste ; HEAD seul n'est pas la base.
Les journaux distinguent les contre-épreuves rouges initiales, les erreurs du
premier correctif et les validations finales. Les assertions PostgreSQL restent
synthétiques locales : aucune session Supabase/PostgREST de production utilisée.
Le verdict de livraison est GO revue indépendante locale, pas GO déploiement.

## SEC-005.1 — Cohérence des reprises manuelles

Le parent immuable reste le patch SEC-005
`19a3e3c9d1fe4e3f6c371318e7dd7fa2e214758d1ffac0c25d63e8032fb534e0`.
Le complément est isolé sous scratch/sec0051 ; sa base est le snapshot local
SEC-005 revu, avec manifeste avant/après, et non HEAD seul.

Défaut reproduit : resolve sans trigger prend l'ancien cache, ou publie sans
propagation/historique s'il n'existe pas ; retry peut choisir une source implicite.
`operation_type` persisté devient l'autorité de l'admission et du worker. Aucun
champ source n'étant persisté par le modèle actuel, retry manuel retourne400 sans
mutation. Une nouvelle réassignation ou resolve avec vidéo explicite est requis.
Une ancienne intention incertaine donne409 à une nouvelle résolution, sans rejouer
son ancienne sélection. Un worker privé de source échoue sans recherche ni cache.
La tentative partagée est journalisée avant exécution puis finalisée même lors
d'un échec fournisseur ou SQL ; un effet partiel n'est jamais déclaré réussi.
Un motif perdu au redémarrage reste absent, sans inventer de valeur historique.

Les tests locaux utilisent les vraies admissions, workers, publications et SQLite,
avec transport SQL synthétique et fournisseur natif remplacé par des octets ID3
synthétiques dans le workspace privé. Un service recréé avec les seules lignes SQL
représente la perte de contexte ; aucun redémarrage de production ni appel YouTube.
La suite backend Linux/Python3.12 reste le gate local. Le complément n'ajoute aucune
migration, variable d'environnement, dépendance, version ou modification client.
La limite mono-processus et les réserves opérationnelles SEC-005 restent valables.

Résultats SEC-005.1 : première contre-épreuve16 cas dont13 rouges et3 témoins
ordinaires verts ; seconde contre-épreuve22 cas dont19 rouges et3 témoins verts,
avec fournisseur borné aussi dans le contrôle HTTP.16 cas corrigés réussis,
puis24 scénarios dans216 tests ciblés réussis et378/378 backend complets sous
Linux/Python3.12 en72,29s, aucun test ignoré. Deux avertissements framework
préexistants (TestClient/httpx et alias authorization pendant le test concurrent
SEC-002), aucun changement Auth. SQL/RLS et migrations inchangés : le harnais
PostgreSQL autonome n'a pas été rejoué pour ce complément ; ses preuves SEC-005
restent historiques. Aucun test Supabase réel ni fournisseur distant exécuté.
GO revue indépendante locale et préparation ultérieure d'un candidat, pas GO
production. Redéploiement backend soumis à autorisation après revue ; aucune
migration, modification de configuration ou mise à jour client requise.
