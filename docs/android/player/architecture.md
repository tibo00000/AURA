# Player Architecture

## Objectif
Isoler la logique de lecture et de queue pour la rendre testable, persistante et moins sujette aux regressions.

## Blocs principaux
- `PlaybackService` : interface entre Media3, session Android et systeme.
- `PlaybackOrchestrator` : applique les regles `play`, `pause`, `next`, `prev`, `seek`.
- `QueueManager` : gere `playback context`, `priority queue`, `history`.
- `PlaybackStateStore` : persiste le minimum necessaire pour reprendre une session.
- `PlayerViewModel` : transforme l'etat metier en etat ecran.

## Cache de streaming et fichiers hors-ligne

`MediaCacheManager` ne gère que le cache Media3 temporaire sous `cacheDir`. Lors d'une
réassignation audio explicite, ce cache peut être invalidé pour la piste concernée. Les fichiers
hors-ligne sous `filesDir/downloads` et leurs liens Room sont durables et ne doivent jamais être
supprimés par une éviction de cache.

## Responsabilites
- Media3 lit la source audio et expose les callbacks bruts.
- L'orchestrateur decide quelle piste doit etre jouee ensuite.
- Le queue manager gere reorder, remove, resume et priorites.
- Le state store sauvegarde contexte, piste courante, position et modes.

## Contrôleurs et sources autorisées (SEC-004)

La confiance est décidée à la connexion Media3 et revérifiée avant résolution :
UID propre pour AURA, confiance système attestée par Media3, ou couple UID/package
et certificat de signature connu pour Android Auto/Assistant. Un nom de package
ou les connectionHints du demandeur ne constituent jamais une preuve.
Les contrôleurs autorisés gardent les commandes de transport et de navigation ;
cela ne leur donne pas le droit de choisir une URI audio arbitraire.

Chaque élément externe (y compris listes, ajout/remplacement et chemins legacy)
est reconstruit depuis une piste du catalogue local. Inconnu ou malformé : refus
du lot entier avant changement de file. Les sources fournies par le demandeur,
DRM, sous-titres et métadonnées de requête ne sont jamais réutilisées comme sources.
Le contrôleur interne peut également lire une piste cloud AURA encore absente de
Room, uniquement par son identifiant et l'endpoint privé reconnu. Les sources
locales proviennent de Room ; aucun fichier utilisateur n'est modifié par ce filtre.

Indépendamment des contrôleurs, le transport streaming n'autorise le bearer que
sur l'origine HTTPS configurée (schéma/host/port exacts) et le chemin
`/me/sync/files/{track_id}` à un seul segment sûr. Aucun query-token, fragment ou
userinfo. Les redirections HTTP sont toutes refusées par le transport effectif,
y compris HTTPS vers HTTPS : pas de seconde requête vers une destination Location.
Le refresh401, Range206 et le cache Media3 restent applicables. Les chemins locaux
restent traités par DefaultDataSource, sans bearer réseau.

Implémentation : `ControllerTrustPolicy`, `ControllerMediaResolver`,
`PrivateStreamPolicy`, `SessionHttpDataSource` et `NoRedirectAudioDataSource`.
Le transport utilise l'OkHttp déjà disponible, sans nouvelle dépendance ni changement
Media3 (1.4.1). Les certificats publics release Auto/Assistant proviennent de
[l'allowlist officielle UAMP](https://github.com/android/uamp/blob/main/common/src/main/res/xml/allowed_media_browser_callers.xml) ;
la vérification Android `hasSigningCertificate` permet la prise en compte de la
filiation de signature. Aucun certificat debug ni nom d'émulateur n'est approuvé
par cette politique. La confiance système Media3 inclut les permissions de
contrôle média/notification accordées par Android, pas seulement SystemUI.

Validation SEC-004 locale : 21 ciblés avec copies isolées, puis94 tests Android
sans ces copies. Le chemin direct étranger était déjà sans bearer avant SEC-004 ;
la redirection HTTPS était suivie mais le JVM retirait le bearer à destination.
Cela ne prouve pas le comportement d'HttpURLConnection sur tous les appareils.
Les nouveaux tests observent les headers sur de vraies sockets loopback HTTP/TLS
et exigent zéro requête vers la cible d'une redirection. Binder inter-applications,
Bluetooth, Android Auto réel et signatures installées restent à vérifier sur
appareil avant publication. Rapport et patch isolé : `scratch/sec004/`.

## Isolation du cache privé entre comptes (SEC-004.1)

Le cache streaming privé est partitionné par une clé versionnée contenant les
empreintes de l'origine HTTPS canonique et de l'identité stable, puis l'identifiant
de piste encodé. Ni bearer, email, epoch de session ni clé arbitraire du contrôleur
ne participent à cette clé. Les anciennes entrées sans propriétaire ne sont jamais
relues. Le plafond LRU reste globalement300Mo ; aucun favori durable n'est purgé.
Un nom de clé v2 ne prouve pas sa provenance : avant tout hit, un marqueur custom
de métadonnées, écrit uniquement par le nouveau chemin applicatif, est exigé.
Une ancienne entrée sans ce marqueur est invalidée de façon ciblée avant usage,
même si son ancienne customCacheKey imitait exactement une clé privée v2.

Une garde entoure CacheDataSource, y compris les cache hits : elle capture une
identité/génération cohérente avant open, vérifie chaque lecture et ne publie les
octets dans le buffer appelant que sous une génération encore valide. Le miss
réseau est lié à cette même portée, sans bascule de compte pendant validSession
ou refresh. La rotation du bearer du même compte conserve portée et clé. Le compte
local actif peut relire son propre cache hors ligne ; cela ne revalide pas les
droits distants à chaque hit. Logout invalide ces lectures.

Le service retire les sources réseau privées et arrête/vidange une source privée
active lors d'une invalidation de portée, sur le looper du lecteur. Les fichiers
locaux et la bibliothèque restent intacts. Les octets déjà envoyés au matériel
avant le traitement de cet événement ne peuvent être retirés rétroactivement.
L'éviction après réassignation cible exactement origine/piste, pour tous les
propriétaires ; les anciens lecteurs de cette ressource sont invalidés. Les I/O
du cache et du transport restent hors Main Thread.

Implémentation : `PrivateCacheDataSource` et `PrivateStreamCache` portent les clés,
la garde autour du cache et la sérialisation par origine/piste de l'éviction et
des lecteurs. Les moniteurs de ressource peuvent contenir des I/O sur les threads
de chargement ; le chemin cache ne prend le verrou de session que pour contrôler
l'état et publier la copie mémoire finale. Ordre de verrous : ressource, puis
session ; aucune I/O cache/réseau sous le verrou de session. La persistance de la
paire conserve sa section critique existante, sur IO. Un échec d'éviction bloque cette ressource jusqu'à
une invalidation complète. Le dernier fragment d'un ancien writer est retiré à
sa fermeture, sous le même moniteur, avant qu'un autre lecteur puisse le voir.
`PrivatePlaybackGuard` traite l'invalidation sur le looper du lecteur. Le service
initialise les préférences chiffrées sur IO et attend l'installation de cette
observation avant d'admettre un média ; aucun réseau ne bloque le Main Thread.

Validation locale SEC-004.1 : 40 tests ciblés puis116 tests Android normaux,
sans ignoré ; les quatre assertions d'isolation échouent avec le câblage cache
historique isolé. Un WAV TLS réel atteint READY avec un buffer positif dans
ExoPlayer/Robolectric ; refresh le conserve, changement de compte le retire.
Une cinquième reproduction, ancienne clé personnalisée imitant v2, échoue avant
la garde de provenance et passe après. Le benchmark50k préexistant a dépassé son
seuil lors d'un passage final, puis passe à la relance sans changer son seuil.
Un premier run complet a subi un crash natif SQLite du harnais ; fermeture
explicite des providers temporaires, puis relance complète réussie. Cela ne
remplace pas un essai sur appareil, avec AudioTrack/Bluetooth réels. Rapport,
patch incrémental contre SEC-004 et empreintes : `scratch/sec0041/`.

### Réserves avant gel APK (22 octobre 2026)

L'invalidation de cache change durablement le namespace de la ressource **avant**
la suppression des spans. Cette génération, sans token ni identité en clair, est
conservée dans un fichier remplacé atomiquement sous noBackupFilesDir, indexé par UID du SimpleCache
et empreinte origine/piste. Une éviction incomplète reste bloquante dans le processus ;
après redémarrage, les anciennes générations restent inaccessibles, même si leurs
spans n'ont pas pu être supprimés. Elles restent soumises au plafond LRU global.
Un fichier de génération illisible ou malformé interdit la lecture ; aucun retour
silencieux à une ancienne génération. Les générations ne sont jamais réutilisées,
y compris lors d'une nouvelle tentative d'éviction ou d'une fermeture tardive.
Les favoris durables et Room sont hors périmètre. Une perte physique du stockage
après confirmation d'une écriture dépasse la garantie applicative de redémarrage.
Le contenu est synchronisé avant renommage atomique et relu avant éviction.
Si cette préparation échoue, l'éviction n'a pas commencé et la réassignation
n'est pas déclarée effective ; aucun repli non atomique n'est utilisé.

L'admission Media3 attend une initialisation réussie de l'observation de session.
Un échec la termine explicitement ; chaque attente a un délai maximal de 30 s.
La destruction du service clôt également l'attente. Une initialisation tardive
ne peut réouvrir une admission déjà échouée ; aucun contournement de l'authentification.

## Donnees persistantes minimales
- identifiant de la piste courante
- position courante
- contexte de lecture
- contenu de la priority queue
- modes shuffle et repeat

## Code Mapping
- `android/app/src/main/java/com/aura/music/service/PlaybackService.kt` : `MediaSessionService` Media3, initialise ExoPlayer et expose `MediaSession`
- `android/app/src/main/java/com/aura/music/domain/player/PlaybackOrchestrator.kt` : orchestre play, pause, next, prev, seek ; utilise `QueueManager` et `PlaybackStateStore`
- `android/app/src/main/java/com/aura/music/data/player/QueueManager.kt` : gere `playback context`, `priority queue` et `history` en memoire
- `android/app/src/main/java/com/aura/music/data/player/PlaybackStateStore.kt` : persiste le snapshot de reprise via `PlaybackSnapshotDao`
- `android/app/src/main/java/com/aura/music/ui/player/PlayerViewModel.kt` : transforme l'etat metier en etat ecran, met a jour la progression periodiquement
