# Security And Secrets

## Principes
- aucun secret en dur
- separation claire entre variables Android et serveur
- moindre privilege pour toutes les integrations

## Variables a prevoir
- URL et cle publique Supabase
- secrets serveur Supabase
- cles provider externes si necessaires
- configuration des workers et jobs

## Regles
- documenter toute variable dans un exemple dedie
- masquer les secrets dans les logs et via MCP
- segmenter les environnements local, staging personnel et production personnelle

## Authentification des routes privées

L'identité provient exclusivement d'un access token Supabase vérifié dans `app/core/auth.py`.
`Authorization: Bearer <JWT>` reste prioritaire sur `?token=<JWT>` ; le paramètre accepte aussi
`Bearer <JWT>` pour les lecteurs audio. Un en-tête invalide ne peut pas être remplacé par le paramètre.
Les échecs restent des HTTP 401 avec `WWW-Authenticate: Bearer` et un objet `detail` FastAPI.

- `SUPABASE_URL` définit l'émetteur attendu `<SUPABASE_URL>/auth/v1` et doit être une URL HTTPS
  sans identifiants, paramètres ni fragment. La confiance ne provient jamais du JWT reçu.
- ES256 et RS256 utilisent exclusivement les clés publiques de
  `<SUPABASE_URL>/auth/v1/.well-known/jwks.json`. Le `kid` doit désigner une clé de même algorithme.
  Le cache JWKS local dure 10 minutes ; les actualisations sur clé inconnue sont espacées
  d'au moins 30 secondes. Le cache Supabase en amont s'ajoute à ces délais : voir la procédure
  de rotation ci-dessous. Rafraîchir la session ne purge aucun de ces caches.
- HS256 exige explicitement `SUPABASE_JWT_SECRET`, réservé au serveur. Aucune clé publique,
  clé `anon` ou `service_role` n'est utilisée comme secret de signature.
- Signature, expiration numérique finie, audience `authenticated`, émetteur exact, rôle
  `authenticated` et sujet UUID sont obligatoires. L'UUID est normalisé après vérification.
- Configuration absente, clé inconnue, JWKS indisponible ou token invalide : authentification
  refusée, sans décodage de secours. Les accès JWKS sont effectués hors de la boucle événementielle.
- Aucun UUID fixe ne constitue un justificatif d'identité. Les clients doivent disposer d'une
  session Supabase valide ; les sessions expirées doivent être rafraîchies ou reconnectées.
- Le routeur principal n'importe et ne monte aucune route `/test`, quel que soit `APP_ENV`.
  Les routes publiques de catalogue et de santé conservent leur contrat.

Ces contrôles n'assurent pas la révocation instantanée d'un access token déjà émis : sa durée de
validité et le cache des clés restent applicables. L'isolation des objets doit aussi être imposée
par les autorisations et RLS, indépendamment de l'authentification.

Références : [signatures Supabase](https://supabase.com/docs/guides/auth/signing-keys),
[vérification PyJWT](https://pyjwt.readthedocs.io/en/stable/usage.html).

## Cache JWKS et procédure de rotation/révocation

Le vérificateur AURA conserve le jeu de clés pendant 600 secondes dans chaque processus,
par émetteur. Il ne conserve pas les clés individuelles indéfiniment (`cache_keys=False`).
Le chargement est effectué à la demande, sans rafraîchissement en arrière-plan ; le délai
réseau est de 5 secondes. Une clé connue utilise le cache jusqu'à son expiration. Une clé
inconnue peut provoquer un rechargement, avec un cooldown local de 30 secondes après un
chargement réussi ; ce mécanisme ne garantit pas que le serveur amont fournisse déjà la clé.

Supabase documente 10 minutes de cache sur son endpoint public JWKS. Les deux caches peuvent
donc cumuler environ 20 minutes de retard, davantage si un proxy ou cache supplémentaire
est ajouté. Ce délai nominal n'est ni un engagement de disponibilité ni une révocation
instantanée. Un redémarrage AURA vide seulement son cache local : l'amont peut encore renvoyer
un jeu de clés antérieur. Les garanties propres aux services Supabase ne s'étendent pas au
vérificateur local FastAPI. Une erreur de chargement ne doit jamais conduire à accepter des
claims non vérifiés ; un jeu encore valide en cache reste utilisable jusqu'à son expiration.

Procédure de rotation planifiée, à exécuter seulement après autorisation sur l'environnement nommé :

1. Publier la clé asymétrique en attente sans révoquer la clé active. Confirmer son algorithme
   autorisé et sa présence dans le JWKS public de confiance, sans exporter de session utilisateur.
2. Prévoir au moins la fenêtre cumulée de 20 minutes, plus une marge d'exploitation. Confirmer
   le renouvellement du jeu de clés dans tous les processus/réplicas ; un contrôle dans un
   processus séparé ne prouve pas l'état des workers actifs. Un redémarrage contrôlé, après
   propagation amont, peut remplacer cette purge locale. Le temps écoulé seul n'est pas une preuve.
3. Activer la clé, puis exécuter le protocole de session de test ci-dessous avec une connexion
   fraîche et un rafraîchissement. Vérifier la continuité des tokens encore valides de l'ancienne
   clé pendant la période de transition. La présence d'une clé dans le JWKS ne prouve pas son activation.
4. Avant révocation normale, attendre l'expiration des derniers tokens signés par l'ancienne clé,
   selon la durée maximale réellement configurée et une marge ; ne pas supposer une durée universelle.
   Révoquer ensuite, laisser propager le JWKS, renouveler les caches de tous les workers et vérifier
   avec une session de test dédiée le refus de l'ancienne signature et l'acceptation de la nouvelle.

En cas de compromission, la révocation urgente exige un plan d'incident distinct : révoquer la
clé, restreindre temporairement l'accès privé si le délai de cache est inacceptable, confirmer
sa disparition côté amont puis vider les caches de tous les processus avant réouverture.
Le lot 1 ne fournit aucun endpoint de purge globale, denylist de clés ou vérification distante
de session à chaque requête ; ne pas annoncer une révocation immédiate.

HS256 ne dépend pas du JWKS : une révocation côté Supabase ne retire pas le secret encore
configuré dans AURA. Son retrait/remplacement et le renouvellement de tous les processus
sont nécessaires, en coordonnant les sessions et clés API héritées. De même, une déconnexion
ou suppression d'utilisateur ne garantit pas le rejet immédiat d'un access token localement
vérifiable avant son expiration. Aucune rotation ni révocation n'est autorisée implicitement
par un test de connexion.

Source : [Supabase, découverte et cache des clés](https://supabase.com/docs/guides/auth/signing-keys#public-key-discovery-and-caching).

## Protocole de connexion avec une session Supabase de test

Objectif : confirmer que de vrais access tokens émis par le projet de test passent le même
vérificateur que les clients, sans parcourir de données privées ni publier un credential.
Ce protocole peut être automatisé après désignation de l'environnement et accès au compte dédié ;
sa rédaction et les tests synthétiques ne constituent pas une connexion réelle réussie.

Préconditions : projet de test ou préproduction explicitement désigné, compte dédié sans
données personnelles, artefact backend revu installé dans un environnement isolé, URL HTTPS
de confiance et clé publique de ce seul projet. Aucun compte ni table de production ne doit
être utilisé. Préparer un fichier audio synthétique uniquement si la partie streaming est exécutée.
Vérifier d'abord les protections de logs avec un marqueur synthétique selon
`docs/ops/logging-observability.md`. Ne pas saisir un vrai token dans une commande shell,
une URL de navigateur, une variable persistante, un journal ou une capture d'écran.

### Préparer le projet et le compte isolés

Étapes à réaliser par le propriétaire du projet avant les appels Auth :

1. Créer dans le Dashboard Supabase un **projet vide distinct**, par exemple `aura-security-test`.
   Vérifier son nom avant chaque action. Ne pas restaurer de sauvegarde, cloner de branche avec
   des données de production, réutiliser les clés de production ou relier les clients habituels.
   La création du projet et son éventuel coût restent une décision du propriétaire ; aucune
   création distante n'est incluse dans une autorisation d'audit en lecture seule.
2. Dans Authentication du seul projet de test, activer Email/Password et ajouter un unique compte
   dédié via la gestion des utilisateurs. Utiliser une adresse de test contrôlée par l'opérateur,
   un mot de passe unique conservé dans son gestionnaire, et confirmer l'adresse pour permettre
   la connexion. Ne pas créer ce compte dans le projet de production. Ne pas utiliser l'inscription
   anonyme ni désactiver la confirmation email du projet de production.
3. Relever localement l'URL HTTPS, la clé API publique et le mode de signature du projet de test.
   Le test Auth/JWKS ne nécessite ni mot de passe de base, ni clé `service_role`, ni export de clé
   privée de signature. Ne transmettre aucun secret dans la conversation. Le mot de passe sera
   saisi dans une invite locale masquée (`getpass`), pas dans une commande ou un fichier du dépôt.
   Pour les clients historiques AURA, distinguer une clé `anon` JWT et une clé publishable : le
   probe HTTP peut utiliser la clé publique ; cela ne valide pas tous les SDK/clients historiques.
4. Ne créer **aucune table, migration ou bucket Storage** pour ce contrôle minimal. La route
   privée éphémère teste l'authentification sans base. Le streaming existant lit un fichier local :
   générer un court WAV silencieux et son JSON de métadonnées dans un stockage backend temporaire,
   dans le dossier hashé du seul UUID de test. Ne pas utiliser les endpoints de téléchargement
   provider ou d'upload pour préparer le fichier ; aucun accès à une bibliothèque privée nécessaire.
5. Désigner le projet de test et autoriser les appels de connexion, refresh et logout de ce seul
   compte. Préparer un backend local isolé avec `_env_file=None`, tâches de récupération/backfill
   désactivées, debug désactivé et aucun volume ou secret de production. Une instance Caddy
   éventuelle doit aussi être isolée ; sa création sur le VPS requiert une autorisation séparée.

Après ces étapes, l'agent pourra automatiser les appels Auth, la validation cryptographique réelle
avec JWKS HTTPS, les refus attendus, le refresh, la génération/lecture/Range du WAV et le rapport
sans credentials. La sonde locale `server/scripts/verify_security_lot1_local.py` couvre déjà les
identités et JWT synthétiques ; elle n'a aucun mode d'appel Supabase réel et ne demande aucun secret.
La sonde opérateur `infra/security-lot1/session-probe.py` réalise ces appels après autorisation ;
elle n'est pas lancée par le backend. `windows-session.py` garde les credentials sous DPAPI
CurrentUser dans `scratch/security-lot1-credentials/session.dpapi` (dossier ACL utilisateur/SYSTEM,
ignoré par Git), les transmet par stdin à une image locale vérifiée, jamais par argv ou rapport.
La clé privilégiée de création reste utilisée sur le VPS, sans extraction ; les JWT/refresh tokens
restent uniquement dans le processus de sonde. Aucun chargement de `app.main` ni accès aux tables
ou fichiers utilisateurs : le stockage audio de la sonde est temporaire et isolé.
Pour tester une expiration réelle, conserver un token uniquement en mémoire jusqu'à son `exp`,
avec une marge d'horloge, puis vérifier 401 sans altérer la signature. Prévoir la durée effective
de la session de test ; un JWT artificiellement vieilli ne remplace pas cette preuve.
Conserver ES256/RS256/HS256 en tests synthétiques ; ne pas tourner les clés de production pour
tester chaque algorithme. Un projet asymétrique neuf ne prouve pas la compatibilité des anciennes
sessions HS256 de production. Cette compatibilité exige une configuration serveur adéquate et
une session dédiée représentative autorisée, ou reste explicitement non validée.

### Exception autorisée pour préparer la fenêtre A+B

Le 2026-10-17, l'opérateur a demandé « crée en un » pour un compte AURA réservé aux tests dans
le projet de production. Cela autorise un seul utilisateur Auth normal, adresse réservée
`aura.invalid` sans email envoyé, mot de passe aléatoire chiffré localement et marqueur
`app_metadata.security_test=SEC-001-lot1`. Le contrôle du catalogue de déclencheurs `auth.users`
n'a trouvé aucun trigger utilisateur avant création. Aucun compte anonyme, rôle privilégié,
bibliothèque, table, bucket, migration, clé ou paramètre Auth n'est créé/modifié par ce protocole.
Les appels connexion/refresh/logout sont limités à ce compte. Cette exception ne transforme
pas le backend dev partagé en environnement isolé et n'autorise pas de déploiement.

Une connexion et un refresh de ce compte, vérifiés cryptographiquement par le code exact du lot 1
dans l'image locale, prouvent le mode des tokens obtenus du projet réel. Le seul JWKS ES256
ne le prouve pas. L'état « current/previously used » et la durée des anciens tokens restent à
confirmer dans JWT Signing Keys et les paramètres Auth du Dashboard, sans afficher de clé.
Le backend audité n'a pas de secret HS256 : si des sessions HS256 non expirées doivent rester
compatibles, STOP avant A+B jusqu'à décision explicite (secret serveur adéquat ou reconnexion
coordonnée après renouvellement). Ne pas copier la clé `anon/service_role` comme secret JWT,
ni ajouter un appel distant de secours au vérificateur pour faire passer le contrôle.

L'expiration réelle exige de laisser le premier token expirer sans l'altérer, en mémoire avec
`--wait-expiry`. Effectuer cette attente **avant** les 30 minutes de maintenance ; aucun changement
de TTL/horloge de production. Le rapport intermédiaire « pending_real_expiry » n'est pas un succès.
Les essais de streaming de cette sonde utilisent la vraie session et la route existante, avec un
WAV local jetable ; ils ne prouvent pas le trafic Caddy/stockage du service cible. La vérification
cible demande une autorisation distincte pour deux fichiers synthétiques et leur retrait précis
dans le seul espace du compte dédié, sans upload/provider ni lecture de bibliothèque privée.

Résultat réel constaté le 2026-10-17 : login 200 et refresh 200 en ES256, validation cryptographique
par l'image locale du lot 1 et JWKS réel, route privée 200, refus attendus 401, streaming WAV
temporaire 200/Range 206 avec octets comparés. Le premier token conservé en mémoire a expiré
naturellement : refus 401, puis logout local 204 confirmé ; aucun token persisté. Ces résultats
ne constituaient pas un essai d'authentification sur le backend de production avant A+B.

Après autorisation et reprise SEC-001.6 le 2026-10-17, l'image B identifiée a été déployée puis
réouverte à 19:44:42+02:00. Les marqueurs synthétiques ont validé la rédaction des logs avant
les vrais tokens. La session dédiée a validé le streaming interne200/206, puis une connexion
et un refresh réels200 ont fourni la session acceptée en HTTPS via Caddy200/206 ; refus401,
/test404 et logout réussi. Deux WAV+JSON synthétiques ont été supprimés, reçus et absence des
quatre chemins exacts contrôlés. Aucune bibliothèque privée ni donnée métier lue/créée.
La sonde réelle HTTPS part du conteneur cible vers le domaine public ; les sondes extérieures
Windows sont sans session. L'expiration naturelle401 reste la preuve préalable dans la même
image, sans nouvelle attente sur le VPS. Le refus d'un refresh après logout et les anciennes
sessions HS256 ne sont pas déclarés testés. Voir le [résultat opérationnel](../ops/security-lot1-intervention.md).

### Scénarios à automatiser dans l'environnement désigné

Exécution par l'opérateur :

1. Utiliser un processus Python temporaire sans `.env` monté. Saisir les credentials avec
   `getpass`, sans arguments CLI ni historique. Garder access token et refresh token uniquement
   en mémoire. Désactiver les traces HTTP, dumps de réponses, debug et affichages de variables locales.
2. Appeler en HTTPS `POST <projet-de-test>/auth/v1/token?grant_type=password`, en-tête `apikey`
   public et corps JSON contenant `email` et `password` du compte dédié. Refuser les redirections.
   Attendre HTTP 200 ; ne conserver dans le résultat de test qu'un booléen de succès et le statut.
3. Construire une application FastAPI éphémère avec une seule route `/auth-check` et
   `Depends(get_current_user)`, sans importer `app.main`, les routes métier ou les clients de base.
   Fournir au vérificateur des `Settings(_env_file=None)` du projet de test ; l'injection porte
   seulement sur la configuration, jamais sur le JWT, le JWKS ou la fonction d'authentification.
   La route compare en mémoire l'identité vérifiée à celle de la réponse Auth, et retourne seulement
   `{"authenticated": true}`. Ne pas retourner ni journaliser l'UUID ou le token. Utiliser
   `TestClient` sans serveur HTTP, ou un serveur loopback sans access log ; debug reste désactivé.
4. Contrôler HTTP 200 pour le Bearer header, le query-token brut et le query-token préfixé Bearer.
   Contrôler HTTP 401 sans token, avec l'UUID seul et avec une signature altérée. Un header invalide
   accompagné du query-token valide doit rester à 401. Aucun handler métier ne doit être appelé.
5. Appeler `POST <projet-de-test>/auth/v1/token?grant_type=refresh_token` avec le refresh token
   uniquement en mémoire. Vérifier le nouveau token par le même chemin et la même identité en mémoire.
   Pour le cas expiré, laisser expirer un access token du compte dédié sans modifier sa signature,
   puis attendre 401 ; ne pas raccourcir la durée des sessions de production pour ce test.
6. Pour le streaming réel via Caddy, utiliser exclusivement un backend isolé relié au projet de test,
   un stockage jetable et l'audio synthétique. Après contrôle des logs, demander
   `/me/sync/files/<id-synthétique>?token=...` avec un client HTTP en mémoire, puis un Range valide
   (200 pour le fichier complet, 206 et octets attendus pour le Range dans l'artefact testé).
   Ne pas appeler `/me/settings`, bootstrap ou une liste de bibliothèque
   de production. Ne pas réutiliser les volumes de fichiers du VPS de production.
7. Déconnecter uniquement la session de test via `POST <projet-de-test>/auth/v1/logout?scope=local`
   et jeter les credentials en mémoire. Ne pas exiger que l'access token déconnecté soit immédiatement
   rejeté : la limite de validation locale décrite ci-dessus reste applicable.

Preuve à conserver : date, version/digest du backend, Python et système utilisés, algorithme,
statuts attendus/obtenus et contrôle des logs sous forme de booléens. Ne conserver ni URLs avec
query-token, ni bodies Auth, ni UUID/email, ni credentials. Toute étape non exécutée reste marquée
« à effectuer ». Ce protocole ne teste pas les autorisations métier des autres lots.

## Checklist opérationnelle avant et après déploiement

| Contrôle | Preuve requise / limite |
|---|---|
| Artefact | Empreinte du code Auth revu, image/digest, Python 3.12 et dépendances réellement déployés ; ne pas assimiler une image locale à la cible. |
| HTTPS et configuration | `SUPABASE_URL` HTTPS de confiance sans userinfo/query/fragment, issuer exact, debug désactivé ; contrôle des paramètres actifs du processus sans extraire les secrets. |
| JWKS | Disponibilité HTTPS depuis le réseau du backend, `kid`/algorithmes attendus, prise en compte des 10 minutes amont et 600 s par processus ; aucune rotation pendant cette vérification. |
| Sessions | Connexion, route privée, refus invalide/expiré, refresh et streaming/Range d'un compte isolé ; confirmer le mode actif et les signatures encore acceptées, y compris HS256 si applicable. JWKS seul ne décrit pas toutes les sessions. |
| Logs | Avant toute vraie session : marqueurs absents de Caddy accès/erreur, Uvicorn, application et collecteurs ; requêtes/erreurs toujours traçables. Vérifier aussi accès directs et logs des composants intermédiaires. |
| Retour arrière | Conserver digests/configurations sans secrets et un artefact de repli vérifié qui garde les deux bypass et `/test` supprimés. Si aucun repli sûr n'existe, prévoir restriction des routes privées pendant la réparation, avec autorisation. Ne pas republier l'image vulnérable de départ. |
| Après déploiement autorisé | Santé 200, `/test` et variantes 404, refus 401 des identifiants synthétiques invalides, vraie session dédiée et refresh, streaming/Range, absence de marqueurs dans tous les logs. Ne pas introduire de données persistantes en production pour ces contrôles sans autorisation. |

Les tests locaux non destructifs sont autorisés ; les appels Auth nécessitent un compte/projet dédié.
L'audit du VPS en lecture seule couvre la configuration active, les empreintes et des compteurs
de logs calculés sur place, sans extraire de secrets ou journaux bruts. Une impossibilité SSH
laisse ces contrôles non exécutés. Une modification de lancement Uvicorn, de configuration Caddy,
de règles réseau ou de collecteur, un redémarrage/reload, la création de ressources distantes,
un déploiement ou une migration ne font pas partie de cet audit et exigent leur propre autorisation.

## Matrice de remédiation — état inspecté le 2026-10-16

Les identifiants de findings conservent ceux de l'audit original ; les numéros de lots suivent le plan de remédiation.
L'état initial ci-dessous est une revue statique des chemins présents à la révision
`c038d4b3`, sans tests offensifs distants. L’autorisation limitée au lot1 concernait
la livraison initiale ; les activations ultérieures des lots2/3 sont documentées ci-dessous.
Les tests « manquants » décrivent les preuves nécessaires, sans prétendre qu'ils existent.

| Finding / lot | État initial et risque | Correction et fichiers concernés | Tests existants / manquants | Dépendances et impact externe |
|---|---|---|---|---|
| 1 / 1 — UUID propriétaire | Présent : `get_current_user` accepte l'UUID fixe sans preuve ; usurpation du propriétaire (élevé). | Retirer le bypass dans `server/app/core/auth.py`. | `test_global_cache.py` utilise le bypass ; remplacer par JWT signé, refuser UUID par header et query, vérifier deux identités distinctes. | Vérificateur fiable du finding 3 ; backend à redéployer, reconnexion des clients utilisant le bypass. |
| 2 / 2 — Propriété Supabase | Confirmé avant SEC-002 : client privilégié et contrôles ID/parent incomplets ; six reproductions synthétiques. Correction active en production depuis le2026-10-18. | Client JWT par requête, propriétaires/parents, RPC invoker et migration `20261017200822_secure_user_data_isolation.sql` ; matrice `security-lot2-isolation.md`. | 233 tests embarqués et18 groupes intégrés préalables ; postcheck production11RLS/11guards, ACL/FK/RPC et intégrité conformes ; session ES256/refresh/logout et streaming public200/206 vérifiés. | Migration autorisée enregistrée `20261018203759`, backend figé déployé, lot1 préservé ; aucun changement de version client. Bilan `docs/ops/security-lot2-deployment.md`. |
| 3 / 1 — JWT sans signature | Présent : secret absent implique lecture libre des claims ; identité arbitraire (moyen). | Vérification ES256/RS256 par JWKS et HS256 explicite ; `core/auth.py`, `pyproject.toml`, `.env.example`, docs. | `test_aura_id_codec_and_auth.py` accepte le fallback ; remplacer, ajouter mauvaises signatures/claims, aucune config, panne/rotation JWKS, contrôle positif. | `SUPABASE_URL` fiable ; secret uniquement pour HS256 ; dépendance crypto et accès HTTPS JWKS ; backend. |
| 4 / 3 — Annulation de jobs | Confirmé initialement : effets avant propriété/globs. F1–F6 puis R1/R2 traités ; SEC-003.3 actif en production depuis le21 octobre2026. | Propriété/CAS, supervision, gardes communes, cache natif avec provenance et GET pur ; R1 remplacement atomique audio, R2 éviction neutralisée. Contrats `jobs.md`/`storage.md`. |329 tests embarqués réussis sur VPS,110 empreintes ; session ES256 et streaming200/206 ; vrai téléchargement succeeded, annulation active DELETE204 vide/404 sans résurrection, témoin intact et deux jobs nettoyés. PostgreSQL antérieur97+10 assertions et3 préconditions non rerun. | Lots1/2 préservés, un Uvicorn/workers1 et six Compose ; aucune migration/mise à jour client. Production rouverte après accords/contrôles SEC-003.4 ; JSON personnel non atomique et cache sans éviction restent des limites. |
| 5 / 1 — Routes de test | Présent : import/include inconditionnel de `/test`, moteur yt-dlp sans authentification ni file régulée (moyen). | Retirer l'import et l'inclusion dans `server/app/api/router.py`. | Pas de garde de production ; ajouter 404 HTTP et absence OpenAPI/routes, sans appels downloader, contrôler `/health`. | Aucune migration ; backend ; les scripts historiques `/test` cessent de fonctionner. |
| 6 / 5 — Quota upload | SEC-005 local :50Mio comptés à la réception, deux uploads/compte, réservation du spool/staging et du cache ; alertes non bloquantes15/10min et2Gio/24h. Aucun quota total individuel, conformément au produit collaboratif. | Garde mono-processus, réserve max(10Gio,10%), erreurs413/429/507 ; configuration et fragment Caddy candidat. Compteurs d'alerte volatils et writers externes hors garantie absolue. | Contre-épreuves avant :50Mio+1 et Content-Length mensonger acceptés ; contrôles après, concurrence, capacité, copies/rollback et multipart tronqué dans test_sec005*. | Revue indépendante et validation des tailles réelles/proxy actif avant déploiement ; aucune modification de production. |
| 7 / 4 — Contrôleurs Media3 | Audit historique : URI arbitraire et bearer global (moyen). Avant SEC-004 : connexion/fallback ouverts, fuite directe déjà bloquée, redirection HTTPS sans bearer dans JVM ; appareil non reproduit. Complément : cache par URI réutilisé entre comptes et ancienne clé forgée imitant v2 reproduits. | SEC-004 local : contrôleurs/sources filtrés et transport HTTPS privé sans redirect. SEC-004.1 : clés par origine/compte/piste avec marqueur de provenance, garde autour du cache et misses liés à la même portée ; buffers privés invalidés, favoris préservés. Aucun backend modifié. | SEC-004 :21 ciblés/94 Android. SEC-004.1 :4 assertions rouges historiques et1 clé forgée avant garde de provenance ;40 ciblés/116 normaux réussis. Crash natif du harnais corrigé ; benchmark50k fluctuant, seuil inchangé. Rapports `scratch/sec004/review.md` et `scratch/sec0041/review.md` ; Binder/Auto/appareil restent limites. | Revue indépendante puis APK/validation appareil ; aucune publication ou installation SEC-004/004.1 effectuée. |
| 8 / 5 — Hardlinks audio | Confirmé sur le code initial ; déjà supprimé par SEC-003.2 et candidat SEC-003.3 déployé selon le reçu du21 octobre. SEC-005 confirme localement que l'upload personnel détache son inode sans changer celui de B. | Conserver le temporaire privé ; renforcer préparation audio/JSON et rollback d'erreur ordinaire. Remplacement partagé explicite et propagation native volontaire maintenus, avec historique durable. | Test hardlinks déjà vert avant SEC-005 ; après : séparation privée, propagation A/B, fallback copie, métadonnées/ancien audio conservés après échec, aucune adoption privée. | Finding6 ; journal SQLite dans le volume, aucune migration Supabase ni conservation d'anciens audios. Revue/déploiement encore requis pour SEC-005. |
| 9 / 7 — Parseur MP4 | Présent : `parseMp4Atoms` récursif sans profondeur maximale ni borne parent/fichier complète ; déni de service local (faible). | Borner profondeur/coût, tailles et additions ; `desktopApp/src/desktopMain/kotlin/com/aura/music/desktop/media/DesktopMediaMetadataReader.kt`. | Ajouter MP4 valides, profondément imbriqués, taille étendue et dépassements ; pas de preuve dédiée trouvée. | Build et installation Desktop ; aucune migration backend identifiée. |
| 10 / 6 — Changement de compte | Présent : `SyncOutboxDao` lit tous les pending sans propriétaire ; Android et `DesktopCloudSyncManager` envoient avec le token courant ; données A envoyées comme B (faible). | Associer opérations/état privé au compte, isoler les reprises ; `shared/.../AuraDaos.kt`, `LocalEntities.kt`, Android `SyncRepository.kt`, Desktop manager/session. | Tests Room/sync existants ; ajouter A→B→A, hors ligne, redémarrage, migration et conservation des opérations de A. | Lot 1 ; migration Room et politique des données historiques à concevoir au lot 6 ; mises à jour clients. |

Le endpoint public JWKS du projet configuré a été lu sans credential le 2026-10-16 : il publie
une clé EC ES256. Cette observation ne prouve pas l'algorithme de chaque session encore active
ni la configuration du backend déployé. Les secrets de production ne sont ni lus ni modifiés
pour les tests ; les clés et comptes des tests sont synthétiques.
