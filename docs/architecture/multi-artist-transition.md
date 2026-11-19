# Titres à plusieurs artistes : transition

## Périmètre et autorisation

Android reste la priorité. Les corrections déjà testées de demandes cloud servent
de base (`1d9c7ada`). Le worktree Tauri est indépendant et ne doit pas être modifié.
L'utilisateur autorise explicitement le 19 novembre 2026 l'accès VPS, les migrations
nécessaires et les opérations de transition. Les sauvegardes restent uniquement
sur le VPS ; aucune modification d'authentification ou de protection existante.

## Séquence d'exécution

1. Inventorier les cinq nouveaux téléchargements de Titi. Vérifier les octets ou
   les empreintes audio de deux portions, ainsi que les durées. Sauvegarder les
   fichiers et les lignes concernées, puis réutiliser seulement les anciennes
   copies confirmées. Garder identifiants d'occurrence, ordre et métadonnées source.
   Archiver les jobs désormais sans usage avant de retirer leurs copies personnelles.
   Une correspondance de nom seule ne justifie jamais la fusion d'enregistrements.
2. Ajouter une représentation ordonnée des crédits (`id`, `name`, `provider`,
   `provider_id`, ordre), provenant des tableaux `artists` Spotify et
   `contributors` Deezer. Ne pas découper des noms aux virgules, esperluettes ou
   lettres « x ». Conserver les champs singuliers pour les lecteurs existants.
3. Ajouter la relation titre/artistes à Room avec migration additive, reprise
   certaine de l'artiste principal existant et index inverses. Alimenter ces
   crédits depuis import, cloud et catalogue ; les pages artiste doivent trouver
   aussi leurs collaborations sans multiplier les titres dans une liste.
4. Synchroniser les crédits via les métadonnées conservées par Aura et les API
   existantes, avec validation stricte et évolution additive PostgreSQL. Réutiliser
   les identités fournisseur connues ; ne pas convertir un artiste homonyme sur
   un rapprochement approximatif. Préserver les identifiants des titres, favoris,
   liens MediaStore et éditions manuelles.
5. Bloquer la création automatique d'une nouvelle copie lorsqu'une copie privée
   plausible reste à confirmer. Rendre cette réutilisation possible même après
   l'import initial. Une confirmation « garder séparé » explicite reste autorisée,
   les exclusions et les baux d'acquisition gardent priorité. La proximité de
   métadonnées ne devient jamais une preuve d'identité audio.
6. Rattrapage de transition versionné : inventorier chaque titre une fois,
   compléter les seuls champs manquants à partir d'une référence exacte ou d'une
   correspondance vérifiée, persister l'avancement et reprendre après interruption.
   Les réponses temporaires échouées restent distinctes des titres non identifiés.
   Les ambiguïtés sont comptabilisées, pas choisies arbitrairement. Pas de
   réacquisition audio pendant ce rattrapage, ni travail réseau au démarrage Android.

## Contrôles et livraison

Tests : migration Room avec fichiers/favoris/occurrences existants ; plusieurs
artistes pour un titre, ordre stable et nom contenant une virgule ; consultation
depuis chacun des artistes sans doublons ; transport des anciens et nouveaux DTO ;
conflit avec une édition manuelle, changement de compte et réponses identiques
sans écritures répétées ; reprises et idempotence du rattrapage ; copie cloud
existante et acquisition concurrente ; isolation PostgreSQL et exclusions.

Déployer le backend et ses migrations seulement après validation locale, vérifier
les contrats actifs et l'absence de changement de configuration/sécurité. Préparer
le client avec signature et empreinte vérifiées avant un essai physique. Ne pas
publier une OTA ni modifier les versions pendant les étapes intermédiaires.

Rollback : conserver les champs singuliers et le code serveur antérieur. Arrêter
le rattrapage avant un retour arrière ; ne jamais effacer les associations ou les
marqueurs déjà écrits. Restaurer les doublons uniquement depuis les sauvegardes
privées après contrôle des usages et des écritures concurrentes.

## Algorithme de rattrapage et limites

Le serveur traite uniquement une demande opérateur explicite créée par `server/scripts/enqueue_metadata_transition.py --playlist <ID> --apply`. Le fichier de demande fige l'inventaire personnel, version 1, dans `_metadata_transitions/requests/<hash compte>.json`. Le processus API le reprend au démarrage, conserve le résultat par titre et sauvegarde le JSON antérieur dans le volume privé avant écriture atomique sous le verrou de stockage existant. Aucune copie audio, aucun job d'acquisition, aucune fusion et aucun traitement global non demandé. Les erreurs réseau suspendent la progression ; une référence retirée devient non identifiée sans bloquer le reste. Les marqueurs rendent les reprises idempotentes.

Android attend 30 secondes, puis traite sur IO un inventaire Room unique par compte/version. Un changement de session annule le traitement. Chaque application de résultat compare encore la ligne courante et préserve les éditions concurrentes, fichiers et favoris. Les crédits issus d'une source exacte peuvent compléter un résultat cloud non résolu. L'index de recherche est invalidé par lots uniquement si des champs ont changé. Le travail ne doit pas se répéter pour les titres ajoutés après cet inventaire.

Sans référence exacte, le résultat n'est accepté que si un unique ID catalogue satisfait titre/version, principal connu (ou préfixe principal d'un ancien libellé concaténé), durée à 2,5 secondes et album lorsqu'il est connu. Ce contrôle enrichit les crédits ; il ne prouve jamais l'identité de deux fichiers. Les noms structurés proviennent du fournisseur, jamais d'un découpage du libellé historique.

Sauvegarde du nettoyage : `/root/aura-multiartist-transition-20261119/duplicate-cleanup`, uniquement VPS. Quatre copies personnelles retirées ; jobs archivés, intentions annulées, 265 occurrences conservées. Parallèle reste distinct (empreintes non concordantes).

## Validation avant activation

Migration de production autorisée et appliquée, historique Supabase `20261119165604` ; corps SQL `db03895b30eb086191f45b27daa76d83` (CRLF), RLS et droits inchangés, empreinte des 265 occurrences Titi identique avant/après. Sauvegarde de fonction dans `/root/aura-multiartist-transition-20261119/migration`. 230 tests Android complets, 12 partagés Desktop, puis 11 ciblés sur le candidat final ; 439 backend complets puis 8 ciblés finaux. 97 assertions SQL d'isolation, 10 d'annulation, 3 rollbacks, 9 scénarios playlists. Aucun échec restant. Lint : zéro erreur, 69 avertissements et 3 informations préexistants.

Les corrections d'artiste explicitement confirmées (`artist_correction`) sont préservées ; une référence catalogue contradictoire produit `needs_review`. Les références fournisseur supprimées ou interdites ne bloquent pas l'inventaire ; les pannes temporaires restent à réessayer.

Une réponse partielle ne retire jamais des artistes invités déjà connus. La reprise peut compléter une liste de crédits incomplète lorsque ses identités connues sont incluses dans la réponse vérifiée ; les crédits manuels restent prioritaires. Ce cas est couvert côté Room et serveur.
