# Storage Strategy

## Types d'objets
- pochettes mises en cache
- artefacts temporaires de telechargement
- fichiers audio derives futurs
- exports techniques ou journaux traces si necessaire

## Principes
- Le stockage long terme doit rester limite pour contenir les couts.
- Les fichiers temporaires doivent etre supprimables sans casser les donnees transactionnelles.
- Les references vers des objets binaires vivent en base, pas uniquement dans des conventions implicites.

## Uploads et remplacements collaboratifs (SEC-005)

Un upload personnel ne publie jamais dans le cache global. Il prépare audio et
métadonnées dans des temporaires privés et remplace les entrées, sans ouvrir un
inode existant en écriture. L'ancien couple est conservé si la préparation ou
un remplacement échoue (liens de sauvegarde transitoires, supprimés ensuite ;
repli par copie avec réservation additionnelle si les liens sont indisponibles).
Le remplacement partagé explicite reste volontaire : cache natif remplacé puis
propagation aux seules références natives prouvées, comme en SEC-003.

Avant tout parsing multipart : authentification vérifiée, admission de deux
uploads maximum par compte, réservation de l'espace de spooling et de staging.
Le parseur reçoit au plus51Mio de corps, un fichier de50Mio, huit champs de64Kio
et16Kio d'en-têtes par partie. La limite fichier est comptée dans les callbacks
de réception, indépendamment de Content-Length. Le spool privé est sur le même
volume que le staging, borné à1Mio en mémoire avant débordement disque.
Le handler recompte le fichier, y compris pour un appel local hors route HTTP.

La réserve minimale vaut max(10Gio,10% de capacité) sur le filesystem réel du
volume. Les réservations restantes des uploads simultanés et la publication du
cache global sont coordonnées dans ce processus ; l'espace libre réel inclut
les anciens fichiers et le cache dont aucune purge n'est réactivée. Les écritures
externes et les phases natives non coordonnées ne sont pas des quotas filesystem :
une baisse indépendante peut imposer un refus et n'est pas une garantie absolue
contre un ENOSPC matériel. Les seuils sont configurables, aucun quota individuel
total n'est introduit. Plus de15 admissions/10min et plus de2Gio reçus/24h donnent
uniquement des avertissements (compteurs mémoire, réinitialisés au redémarrage).

L'historique collaboratif est un journal SQLite privé sous
`SYNC_FILES_DIR/_audit/audio-replacements.sqlite3`, dans le volume durable.
Il ne dépend pas d'une nouvelle table Supabase ni d'une migration distante.
Il contient piste, UUID acteur vérifié, tentative interne, horodatages UTC,
empreintes ancien/nouveau audio, opération et motif optionnel énuméré.
L'intention est durable avant publication ; succès seulement après publication,
propagation et statut job confirmés. Échec sans effet, effet partiel et interruption
indéterminée restent distincts. Un ancien état en cours trouvé après redémarrage
n'est jamais converti automatiquement en succès. Aucun ancien audio n'est archivé.
GET authentifié de l'historique est collaboratif ; aucun token/email/URL source
n'y figure. Sauvegarder ce journal avec le volume, en utilisant SQLite backup
ou après arrêt/checkpoint (ne pas copier seulement le fichier principal d'un WAL actif).
Les gardes et limites sont mono-processus ; plusieurs workers exigeraient une
coordination partagée et une nouvelle validation, comme pour SEC-003.

SEC-005.1 : les reprises sont classées par `operation_type` SQL, jamais par un
`trigger` worker. Une résolution manuelle avec choix vidéo explicite contourne
l'ancien cache et conserve propagation et journal, y compris échec/effet partiel.
Sans source persistée, le retry manuel est refusé avant mutation ; sans source
dans une tentative admise, celle-ci échoue et son journal est finalisé en échec.
Les fichiers privés, les limites d'upload et l'absence de purge sont inchangés.

## Fichiers des tentatives de téléchargement

SEC-003.2 : seul le sous-répertoire `_global_cache/_native_v1` constitue le cache
global vérifié. Il contient des sorties de tentatives natives autorisées et un
reçu serveur liant identifiant de piste, origine et empreinte du contenu. Les
anciens caches restent conservés mais ne sont pas promus automatiquement. Les
fichiers privés, leurs noms/métadonnées et les anciennes lignes succeeded ne
constituent pas une provenance de cache public. Les alias servent uniquement
au calcul de clés, jamais à construire un chemin brut.

Un upload reste privé et remplace atomiquement sa propre entrée, sans modifier
un inode partagé existant. Les recherches, indicateurs catalogue et streaming ne
créent aucun cache/alias/hardlink. Un cache froid peut donc demander un nouveau
téléchargement natif ; le propriétaire garde l'accès à ses fichiers personnels.

La propagation d'une réassignation ne remplace que les références personnelles
marquées par le serveur et dont le contenu correspond encore à leur empreinte.
Uploads privés et références historiques non prouvées restent intacts. Remplacement
d'upload, propagation et DELETE de fichier personnel partagent une garde de stockage
mono-processus ; la propagation ne recrée pas une référence supprimée avant sa garde.
Cette suppression personnelle ne révoque pas l'accès au cache public natif.

La réassignation prépare le lien ou la copie dans un temporaire du répertoire
personnel puis remplace l'audio par `os.replace`. Un échec de préparation ou de
remplacement conserve l'entrée et les métadonnées précédentes ; aucun temporaire
de propagation ne subsiste. Les métadonnées ne changent qu'après ce remplacement.

Audio et JSON ne forment cependant pas une transaction : l'écriture personnelle
JSON après réassignation reste en place. Un échec disque ou un arrêt peut laisser
des métadonnées anciennes/tronquées, rendre l'entrée de sync illisible ou empêcher
une propagation ultérieure. SEC-003.4 documente ce risque sans changer le candidat ;
aucune réparation/backfill automatique ni adoption d'un fichier privé n'est permise.

L'éviction `cleanup_orphaned_cache` est désactivée sans option de réactivation :
elle retourne zéro et n'accède ni aux fichiers ni aux verrous. Son verrou historique
utilisait la clé SHA-256 alors que les workers utilisaient l'identifiant piste.
Une coordination commune avec tous les lecteurs/éditeurs est nécessaire avant
toute réintroduction de l'éviction ; aucune purge n'est nécessaire à ce candidat.

Le répertoire de reçus doit rester inscriptible par le serveur uniquement. Une
compromission locale de ce stockage sort de la garantie de provenance HTTP. Les
liens symboliques/jonctions du cache et les entrées non régulières sont refusés.

- Une tentative produit ses sorties dans un répertoire privé sous `_attempts`, nommé
  par un identifiant interne généré par le serveur. Les motifs yt-dlp utilisent un
  nom constant dans ce répertoire, jamais l'identifiant fourni dans une URL.
- Une sortie n'est publiée qu'après contrôle du propriétaire et de la tentative
  courante. Le fichier de travail final garde le chemin compatible `<job_id>.mp3`.
- Le nettoyage d'une tentative attend la fin de son travail natif, reste limité à
  son propre répertoire et ne suit pas les liens symboliques vers l'extérieur.
- Le nettoyage des fichiers historiques utilise des noms littéraux connus. Les
  suffixes non reconnus restent conservés : aucun glob ni recherche approximative
  ne justifie leur suppression. Les fichiers témoins restent intacts.
- L'annulation supprime des liens de travail, jamais le contenu d'un inode partagé
  en place. Le cache global, les hardlinks personnels, les métadonnées synchronisées
  et les fichiers hors ligne publiés ne sont pas effacés par DELETE d'un job.
- Un nettoyage de réassignation ne peut viser qu'un job du même propriétaire,
  dans un état terminal et sans tentative native encore active.
