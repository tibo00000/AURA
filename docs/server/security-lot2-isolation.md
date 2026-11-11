# SEC-002 — Isolation des données personnelles

## Périmètre et invariant

Finding **2 de l'audit initial** : aucune identité vérifiée ne peut lire, modifier, supprimer,
réattribuer ou rattacher une ressource privée d'un autre compte en connaissant son identifiant.
Le lot 1 et les contrats HTTP existants sont conservés. Les sections de préparation ci-dessous
retracent les validations locales ; elles ne remplacent pas une preuve des politiques déployées.
Le2026-10-18, migration et backend lot2 ont été appliqués en production après autorisations,
avec postcheck RLS/ACL/FK/RPC conforme et tests de session/streaming réels réussis : bilan dans
`docs/ops/security-lot2-deployment.md`, section SEC-002.4-RUN. Aucun contrat client changé.

## Matrice avant/après correction locale

| Opération / endpoint | Tables | Client avant | Contrôle avant | Diagnostic | Correction réalisée localement |
|---|---|---|---|---|---|
| GET/PATCH /me/settings | user_settings, profiles | service_role | user_id issu du JWT | Pas d'IDOR direct confirmé ; RLS contournée | Client JWT, filtres conservés |
| GET/PUT /me/playback-snapshot | playback_snapshots | service_role | user_id imposé | Pas d'IDOR direct confirmé | Client JWT, clé utilisateur |
| GET/POST/PATCH/DELETE /me/playlists | playlists, playlist_items | service_role | Propriétaire vérifié, insert sans upsert | Pas d'IDOR direct confirmé sur REST | Client JWT ; erreurs sans détails SQL |
| POST/DELETE /me/playlists/{id}/tracks | playlist_items, playlists | service_role | Parent vérifié avant action | Défense RLS manquante ; cohérence multi-écriture à protéger | Client JWT, parent conservé |
| GET /me/likes, PUT/DELETE /me/tracks/{id}/like | likes | service_role | Clé user_id + track_id | Pas d'IDOR direct confirmé | Client JWT, catalogue partagé inchangé |
| GET /me/history | history_items | service_role | Filtre utilisateur | Pas de lecture intercompte confirmée | Client JWT |
| Bootstrap | profiles, settings, playlists/items, likes, snapshot | service_role | Filtres utilisateur, enfants des playlists filtrées | Pas de fuite directe confirmée | Client JWT par requête |
| Pull-batch | settings, playlists/items, likes, snapshot | service_role | Filtres utilisateur/parents | Pas de fuite directe confirmée ; historique/sessions/events non implémentés dans pull actuel | Client JWT, pas d'élargissement fonctionnel |
| Push playlist | playlists | service_role | ID seul | **Confirmé** : lecture, suppression, modification de B | Client JWT + filtres propriétaire, insert en collision refusé |
| Push playlist_item | playlist_items, playlists | service_role | ID seul ; parent fourni non vérifié | **Confirmé** : divulgation, suppression et ajout dans B | Parent possédé et appartenance de l'item |
| Push playlist_reorder | playlists, playlist_items | service_role | Token du parent, puis update item par ID seul | **Confirmé** : parent A + item B ; liste mixte | Mutation transactionnelle bornée à un seul parent |
| Push settings/like/snapshot | tables correspondantes | service_role | Propriétaire imposé | Pas d'IDOR direct confirmé | Client JWT, filtres conservés |
| Push history_item | history_items | service_role | Upsert global sur ID client | **Confirmé** : écrasement/réattribution de B | Déduplication sans upsert global, session possédée |
| Push listening_session | listening_sessions | service_role | SELECT/UPDATE par ID seul | **Confirmé** : réattribution et fuite de started_at | Filtre propriétaire, création sans écrasement |
| Push playback_event | playback_events, listening_sessions | service_role | ID seul ; session parent non vérifiée | **Confirmé** : divulgation de doublon B et lien intercompte | Client JWT, parent et FK composite |
| Idempotence batch/opération | processed_batches, processed_operations | service_role | PK globale ; prélecture opérations sans utilisateur | **Confirmé** : collision/appropriation des marqueurs | PK (user_id, ID), filtres et conflits alignés |
| Pagination jobs | download_jobs | service_role serveur | Liste par utilisateur, curseur lu par ID seul | Lecture de date étrangère confirmée dans la requête | Curseur filtré par utilisateur ; worker privilégié conservé |

Preuves : `server/app/services/sync_service.py`, `server/app/api/routes/me.py`,
`server/app/api/routes/sync.py`, `server/app/db/supabase.py`, migrations historiques.
Reproductions indépendantes en mémoire du vrai service : item B divulgué, reorder modifiant B,
session/historique réattribués à A et playlist B supprimée. Ce n'est pas une validation SQL.

## Architecture retenue

- Identité exclusivement issue de `get_current_user` du lot 1 ; aucun user_id de payload ne
  décide du compte. Les IDs métier restent des chaînes opaques et les enveloppes inchangées.
- Nouveau client Supabase par requête, clé publique requise, JWT vérifié obligatoire ; aucun
  fallback service_role ni état de session mutable partagé. Service Sync lié à ce client,
  jamais un singleton dont on changerait les credentials.
- Filtres propriétaire explicites conservés/ajoutés et validations des vrais parents.
- RLS et privilèges explicites, y compris liens de session et garde restrictive contre une
  ancienne politique permissive ; upserts impossibles sur les lignes cachées d'un autre compte.
- Les opérations touchant plusieurs positions d'une playlist utilisent une fonction SQL
  `SECURITY INVOKER`, à search_path fermé, verrouillant le parent possédé. Toute liste d'items
  est validée avant mutation, puis les contraintes et le rollback PostgreSQL sont effectifs.
- Le batch reste **non atomique**, opérations ordonnées avec résultats individuels. Une
  opération en échec ne devient pas un marqueur de succès du lot ; pas de promesse nouvelle
  de transaction globale entre la mutation et le journal d'idempotence HTTP séparé.

## Exceptions privilégiées

Le singleton serveur reste réservé au catalogue partagé, aux workers et à la persistance des
jobs. Les contrôles d'accès utilisateur des routes jobs restent obligatoires ; le curseur doit
être filtré lui aussi. Les clients Android/Desktop/Web passent par FastAPI, pas par un accès
direct à la table jobs. Catalogue et jobs ne sont pas convertis aveuglément en client utilisateur.
Les fichiers audio/cookies restent sous le namespace de l'identité JWT et hors refonte du lot 2.

## Migration et compatibilité

Migration : `supabase/migrations/20261017200822_secure_user_data_isolation.sql`.
Appliquée d'abord au projet isolé SEC-002.3, puis à production après autorisation nominative
le2026-10-18 via le wrapper figé ; historique `20261018203759 / secure_user_data_isolation`.
Le candidat autonome et les gates d'application production figurent dans `docs/ops/security-lot2-deployment.md`.
Les marqueurs changent de PK SQL, sans changement d'ID HTTP.
Les FK composites sessions/enfants imposent le propriétaire commun ; la session de l'historique
reste facultative, comme l'exige le client Android. Les contextes informatifs opaques de lecture
ne sont pas déréférencés ni arbitrairement soumis à une FK vers les playlists.

Préflight et validation des contraintes : arrêt sur données incohérentes, sans suppression,
réattribution ou réparation automatique. Appliquer la migration revue **avant** le futur backend ;
le nouveau backend dépend des politiques, clés composites et fonction transactionnelle.
Les APIs et payloads clients restent inchangés ; ne pas modifier les versions applicatives.
Un retour vers l'ancien backend privilégié n'est pas un repli public autorisé.

Le SDK Python minimum devient `supabase>=2.33.0,<3.0` pour le transport HTTP possédé et
fermé par requête (`ClientOptions.httpx_client`, absent de 2.11). La validation utilise
2.33.0. Aucune nouvelle variable d'environnement ; la clé publique existante est obligatoire.
PostgreSQL **15 minimum** est nécessaire pour `ON DELETE SET NULL (listening_session_id)`.
La contrainte d'ordre devient différable, sans changer l'unicité finale. Les migrations
historiques et les données sont conservées ; les dépendances SQL inconnues provoquent un arrêt.

Avant toute application autorisée : inventorier les politiques, grants hérités, vues,
fonctions exposées et leurs appels à d'autres fonctions, triggers et dépendances des PK.
Le préflight bloque les rôles clients privilégiés, les fonctions `SECURITY DEFINER` publiques
exécutables, les triggers privilégiés et les liens historiques incohérents. Il ne remplace
pas l'inventaire des vues et des fonctions présentes uniquement sur la base distante.
Une anomalie impose une décision séparée ; aucune purge ou réattribution automatique.
La transaction utilise `lock_timeout=5s` et `statement_timeout=60s` ; une erreur fait
annuler la transaction. Prévoir une maintenance des écritures lors de l'application future.

Les clés composites des marqueurs exigent la nouvelle cible `on_conflict` : ne pas laisser
l'ancien backend écrire après la migration. Déployer SQL puis backend corrigé pendant la
même fenêtre, conserver le confinement en cas d'échec. Aucun rollback destructif ni remise
en service publique de l'ancien chemin privilégié. Une restauration des données/schema
nécessiterait un plan et une autorisation distincts.

## Validation et livraison

Tests A/B de service/API, collisions réelles simulées dans le faux stockage, concurrence de
clients SDK et tests PostgreSQL réels distincts. Les mocks ne prouvent pas RLS ; les tests SQL
doivent s'exécuter uniquement dans une base locale jetable avec identités synthétiques.
Résultats exécutés le 2026-10-17 : **113 tests ciblés**, puis **192 tests backend** réussis
dans Python 3.12/Linux amd64, réseau désactivé, sans `.env` monté ; aucun échec ou test
ignoré aux dernières exécutions. Une dépréciation Starlette/httpx préexistante reste visible.
**97 assertions PostgreSQL 16.15** et **3 contrôles d'arrêt/rollback du préflight** réussis
dans un conteneur local jetable sans réseau ni port publié. Les six reproductions sur le
vrai service initial ne franchissent plus la frontière A/B avec le service corrigé.

Les tests SQL exécutent les vrais grants, RLS, contraintes et RPC. `auth.uid()` y lit des
claims synthétiques ; ce n'est pas une pile Supabase Auth/PostgREST complète ni une preuve
des politiques déployées. Les tests Python utilisent un stockage simulé pour les handlers,
le vrai SDK avec HTTP synthétique pour la concurrence, et l'authentification du lot 1 reste
couverte séparément. Aucun test Android/Desktop/Web, compte réel ou accès distant effectué.
La revue séparée du candidat immuable n'a trouvé aucun problème vérifiable ; elle ne remplace
pas la revue indépendante demandée avant déploiement.

L'idempotence conserve une limite préexistante : mutation et marqueur passent par des appels
HTTP séparés. Un échec de persistance est signalé, le batch n'est pas marqué terminé ; les
entités additives se dédupliquent par ID et un reorder rejoué peut retourner un conflit.
Il n'existe pas de garantie exactly-once globale entre deux requêtes concurrentes. La RPC
protège atomiquement les mutations des positions, pas tout le batch. Les collisions d'IDs
globaux cachés échouent sans retourner la ligne étrangère ; les erreurs HTTP sont génériques.
Les IDs des contextes de lecture restent informatifs, sans accès induit à leur objet supposé.

Le rapport livré contient les commandes exactes, sorties, empreintes et le diff du lot seul,
calculé depuis le working tree initial (incluant le lot 1), et non depuis HEAD seul.

Aucun déploiement, redémarrage, push, migration distante, modification de secret ou de version.
Arrêt après lot 2 pour revue indépendante ; aucun lot 3 engagé.

## Préparation opérationnelle SEC-002.1

La revue indépendante de l'architecture locale est favorable. Les diagnostics complémentaires
ont confirmé des écritures REST items/updated_at séparées : un entrelacement avec la RPC peut
faire reculer le timestamp, et un échec du touch laisse une insertion REST partielle. Aucun
nouveau franchissement intercompte observé. Ils sont corrigés localement en SEC-002.2 ci-dessous,
avec revue indépendante et validation intégrée encore requises avant activation.
Inventaire distant en lecture seule autorisé puis exécuté : PG17.6, comptes d'incohérences
nuls et aucun blocage SQL automatique, sans appliquer la migration. Test réel Supabase isolé
préparé mais non exécuté ;
plan, droits, points d'arrêt et verdict dans `docs/ops/security-lot2-validation.md`.

## SEC-002.2 — Correction locale de cohérence REST/RPC (historique de livraison)

Implémentation locale : extension de la RPC invoker existante par des actions
REST distinctes (insert sans décalage, suppression de toutes les occurrences d'une piste,
PATCH metadata). Même verrou parent possédé et transaction pour mutation + timestamp ;
timestamp calculé en PostgreSQL après verrou, supérieur à sa valeur précédente même si
l'horloge recule. Les contrôles JWT/RLS et contrats/statuts HTTP existants sont conservés.
Le recul de timestamp PATCH est également reproduit avant correction. Les nouvelles assertions
échouent 5 fois avant correction (timestamps ajout/retrait/PATCH, rollback ajout/retrait), puis
les 24 tests PostgreSQL passent après correction. La migration lot2 étant encore non appliquée,
seul son candidat local est modifié ; nouvelle empreinte à revoir avant toute activation.
211 tests backend Python3.12/Linux, 97 assertions SQL +3 préflights et 9 tests Desktop réussis.
Le runner intégré utilise la liste réelle des playlists et le token du bootstrap ; son OpenAPI
est contrôlé avant Auth/écriture. Aucune session Supabase réelle ni opération distante SEC-002.2.
Limites, commandes, points d'arrêt et GO conditionnel pour validation isolée :
`docs/ops/security-lot2-validation.md`. NO-GO production tant que revue/intégration manquent.
