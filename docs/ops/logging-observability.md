# Logging And Observability

## Logs attendus
- logs API
- logs jobs
- logs providers
- logs de sync

## Exigences
- chaque entree importante contient un timestamp, un niveau et un correlation id
- les erreurs player cote client doivent pouvoir etre rapprochees d'echecs provider cote serveur
- les secrets doivent etre rediges

## Usage
- support au debug manuel
- exposition selective via MCP

## Tokens de streaming transmis par URL

Le paramètre `token` des URLs audio constitue un bearer credential, même en HTTPS. Il peut
être recopié dans des access logs, erreurs de proxy, outils de diagnostic, historique ou Referer
selon le client. TLS protège le transport, pas ces copies. Ne jamais joindre une URL de lecture
authentifiée à un ticket, une capture ou un rapport. Le contrat header/query-token est conservé
dans le lot 1 ; aucun déplacement ou remplacement du token n'est réalisé par cette proposition.

État inspecté : le `infra/caddy/Caddyfile` du dépôt n'active pas les access logs par directive
`log`. Cela ne prouve pas l'absence de logs d'erreur ou de configuration additionnelle sur un VPS.
Le CMD Uvicorn de `infra/docker/server.Dockerfile` ne désactive pas ses access logs : le formateur
de requête Uvicorn inclut la query string. Un filtre Caddy ne protège donc pas à lui seul les logs
du backend ou les collecteurs Docker. Aucun journal de production n'a besoin d'être téléchargé
pour établir ce risque préexistant.

### Configuration distante observée le 2026-10-17

L'audit SSH en lecture seule a inspecté les conteneurs en cours d'exécution, les paramètres du
processus principal et la configuration Caddy obtenue par GET sur son API locale. Les sorties
ne contiennent que des versions, empreintes, booléens et compteurs ; aucun journal brut ou
credential n'a été extrait. Ces observations sont distinctes de la configuration du dépôt.

- Caddy 2.8.4 x86_64 : configuration active identique à l'adaptation du Caddyfile monté,
  access logs HTTP non activés, aucun encodeur `filter`, debug et `log_credentials` non activés.
  Les filtres proposés ci-dessous restent donc à appliquer ; le test local de la proposition
  était ARM64 et ne valide pas cette proposition complète sur le VPS x86_64.
- Les deux backends exécutent Uvicorn avec ses access logs, sans `--no-access-log` ni
  `--log-config`. Des lignes d'accès HTTP sont effectivement présentes dans leurs logs Docker.
  `APP_DEBUG=true` est présent dans l'environnement des processus et `FastAPI` utilise ce réglage.
  Ne pas provoquer d'erreur volontaire sur la production pour confirmer les pages de debug.
- Les accès HTTPS `/health` via Caddy ont répondu 200 avec vérification TLS ; les mêmes routes
  sur les ports publics 8000 et 8001 répondent aussi 200 en HTTP direct. Centraliser les traces
  sur Caddy exige donc de traiter ces accès directs avant de supprimer les access logs Uvicorn.
- Le service nommé « dev » partage le projet Supabase, la configuration de clé privilégiée
  et le volume audio de production. Il ne doit pas servir de bac à sable pour les tests Auth/audio.
- Scan calculé sur place, sur les 168 dernières heures avec une limite de 25 000 lignes par
  conteneur : 2 060 lignes backend prod, 20 560 backend dev, 233 Caddy ; aucun paramètre
  query-token ni chaîne de forme JWT détecté par les motifs utilisés. Le premier échantillon
  sur 24 h était également négatif. Cela ne démontre pas une rédaction des logs, ni l'absence
  historique de fuite : autres fichiers, collecteurs, rétentions et formats non inspectés.
- Les conteneurs utilisent le driver Docker `json-file` ; aucun `max-size`/`max-file` n'est
  renseigné dans leur configuration inspectée. Une rotation ou rétention extérieure à Docker
  n'a pas été validée. Ce point ne change pas le contrat de streaming.

Correction opérationnelle minimale à préparer puis autoriser séparément : désactiver le debug
backend, appliquer les filtres Caddy aux logs d'accès et d'erreur, traiter la publication directe
8000/8001 pour imposer le passage par la trace filtrée, puis désactiver ou rédiger les access logs
Uvicorn. Préserver les erreurs et vérifier la chaîne entière avec des marqueurs synthétiques dans
un environnement isolé avant une vraie session. Cibles : Caddyfile, lancement Uvicorn, configuration
`APP_DEBUG` et publication des ports du Compose ; aucun de ces éléments n'a été modifié par l'audit.
Les résultats détaillés et les limites figurent dans `security-lot1-tests.md`.

### Proposition Caddy à valider avant application

La préparation SEC-001.3 et l'ordre exact confinement/déploiement sont définis dans
`docs/ops/security-lot1-intervention.md`. La proposition a été appliquée et vérifiée sur le VPS
le 2026-10-17 après autorisations A+B et reprise ; les paragraphes d'audit ci-dessus restent
l'état historique avant intervention.

Pour chaque site qui doit produire des access logs, appliquer un filtre qui retire toute la
query string et les en-têtes. Ce choix évite de dépendre du décodage, des répétitions ou de la
casse du seul paramètre `token`, ainsi que d'un Referer contenant une URL authentifiée. Chemin,
méthode, statut, durée et volumes restent disponibles ; ajouter seulement un correlation id maîtrisé
si nécessaire, sans recopier une URL ou un credential dans ce champ.

Bloc candidat, à intégrer dans une copie de la configuration pour revue :

```caddyfile
log {
    output stdout
    format filter {
        wrap json {
            message_key ""
            stacktrace_key ""
        }
        fields {
            request>uri regexp "[?].*$" ""
            request>headers delete
            resp_headers delete
            error delete
            errorVerbose delete
        }
    }
}
```

Appliquer le même `format filter` au logger global `log default` pour les champs structurés
des erreurs, après validation sur la version exacte de Caddy utilisée (`caddy:2.8-alpine` dans
le Compose de référence). Un filtre d'access log ne s'applique pas automatiquement à tous les
autres loggers. Ne pas activer `debug` ou `log_credentials`. Un filtre de champs ne peut pas
nettoyer un token déjà interpolé dans le texte `msg` : ces chemins doivent être évités ou
faire l'objet d'une rédaction à la source. Le candidat SEC-001.3 supprime le message et la
stacktrace libres via les clés de l'encodeur JSON ; `fields { msg delete }` serait inefficace.
Les causes textuelles disparaissent, mais les champs structurés de trace demeurent.
Le simple `query { delete token }` est plus limité
et n'est pas retenu comme protection générale de tous les champs susceptibles de contenir l'URL.

Cette proposition est appliquée au Caddyfile opérationnel depuis SEC-001.6. Les syntaxes `filter`, champs
imbriqués, `regexp` et `delete` sont documentées par Caddy. La variante qui conserve le chemin
a été validée le 2026-10-16 sur Caddy 2.8.4 ARM64 dans un conteneur sans réseau externe : marqueur
absent des access logs et erreurs de proxy pour les statuts 200/401/404/502, chemins et durées
conservés. Cela ne valide ni l'image amd64 du VPS ni sa configuration complète. Ne pas recharger
Caddy dans le cadre d'une revue documentaire. Les chemins ne doivent pas contenir de credentials ;
le filtre ne retire pas un secret placé dans un segment du chemin ou dans `msg`.

### Protection de la chaîne complète et contrôle préalable

1. Valider une copie de la configuration avec `caddy validate --config <copie> --adapter caddyfile`
   dans une instance isolée de la même image ; aucun reload ou serveur public nécessaire.
2. Le candidat SEC-001.3 conserve des access logs Uvicorn structurés sans query et applique
   une liste positive aux erreurs/logs Python, sans message libre, argument, extra ou valeur
   d'exception. Le wrapper ASGI transmet corps et événements sans les lire et ajoute un identifiant
   de requête ; le logger yt-dlp rejoint le même circuit. Le Dockerfile candidat active ce profil.
   La désactivation `--no-access-log`, testée initialement, ne suffit pas à protéger les erreurs.
   Ces changements ont été appliqués sous autorisation SEC-001.6 et ne garantissent pas le filtrage d'une
   future sortie native arbitraire ou d'un collecteur extérieur.
   Confirmer que les requêtes passent effectivement par Caddy : les ports 8000/8001 sont publiés
   dans le Compose de base ; l'audit SEC-001.2 a confirmé leur accessibilité HTTP publique et
   l'absence de garde DOCKER-USER avant A. Après A+B : aucune publication ni écoute hôte8000/8001,
   dev arrêté, tests extérieurs sans HTTP et six gardes temporaires retirées après vérification.
   Ne pas perdre la traçabilité d'un accès direct en supprimant sa seule source d'access logs.
3. Avec un marqueur synthétique qui n'est pas un JWT, envoyer en préproduction une requête de
   streaming refusée et une requête réussie, y compris Range, query-token brut/préfixé/encodé,
   paramètres répétés, puis un 404 et une erreur de proxy simulée localement. Mettre aussi le
   marqueur dans Authorization et Referer. Ne pas utiliser de vraie session pour ce contrôle.
4. Vérifier localement son absence dans les sorties Caddy d'accès et d'erreur, Uvicorn,
   collecteurs et traces applicatives. Rapporter seulement « marqueur absent : oui/non » et
   les catégories de logs contrôlées, sans copier le journal. En cas de présence, arrêter
   avant le test de session réelle et corriger le chemin concerné.
5. Limiter les accès aux journaux et leur rétention selon le besoin opérationnel. Si un token
   réel a déjà été journalisé, traiter l'incident sans redistribuer les logs bruts ; prendre
   en compte la durée de validité des access tokens et les caches décrits dans la politique Auth.

Sources : [filtres de logs Caddy](https://caddyserver.com/docs/caddyfile/directives/log#filter),
[implémentation des filtres Caddy 2.8.4](https://github.com/caddyserver/caddy/blob/v2.8.4/modules/logging/filters.go),
[formateur de requête Uvicorn](https://github.com/encode/uvicorn/blob/0.54.0/uvicorn/protocols/utils.py).

### Limite locale des journaux Docker — candidat SEC-001.3

L'audit VPS initial confirmait `json-file` sans limite pour prod, dev et Caddy. L'activation des access
logs impose une borne : le Compose de confinement propose `max-size: "10m"`, `max-file: "3"`
pour prod et Caddy, environ 30 Mo chacun hors dépassement d'une entrée et métadonnées.
Les options sont des chaînes. Le dev partagé doit rester arrêté, sans recréation pour ce motif.
Ces limites ne changent ni la politique du daemon ni les autres services et ne sont pas une
garantie de nombre de jours conservés. Une recréation ciblée de chacun des deux conteneurs
est nécessaire ; un reload Caddy ou changement de fichier seul ne suffit pas. Vérifier
uniquement `.HostConfig.LogConfig` sur les conteneurs recréés et la conservation des traces
utiles avant de déclarer la protection effective. Aucun nettoyage manuel des fichiers Docker.
Voir les points d'arrêt du [runbook](security-lot1-intervention.md).
[Documentation Docker json-file](https://docs.docker.com/engine/logging/drivers/json-file/).

Application SEC-001.6 vérifiée le 2026-10-17 : prod et Caddy recréés avec `json-file`,
`max-size=10m`, `max-file=3`, options effectives inspectées sur les deux conteneurs. Dev reste
arrêté. Les contrôles synthétiques sur la cible sous maintenance puis après réouverture ont
confirmé l'absence des marqueurs query/Authorization/Referer et la présence des traces d'accès
structurées API/Caddy. Les journaux bruts n'ont pas été exportés. Ce contrôle cible ne simule
pas toutes les erreurs métier/natives/providers ni un collecteur extérieur ; ces limites persistent.

### Portée des preuves locales

`server/scripts/verify_security_lot1_local.py` utilise une app éphémère, le vrai vérificateur
Auth et un fichier WAV généré dans un répertoire temporaire. Les requêtes portant des JWT
synthétiques utilisent TestClient sans journal HTTP ; les essais Uvicorn emploient exclusivement
un marqueur non JWT. La sonde compare access logs activés/désactivés sur 16 requêtes chacun
(200/401/404/500) et exige la conservation des erreurs applicatives et tracebacks.
Le marqueur apparaît uniquement dans les access logs Uvicorn activés. Cette absence dans les
erreurs contrôlées ne constitue pas une preuve de rédaction générale : les exceptions des providers,
clients HTTP, collecteurs Docker et autres chemins métier restent à contrôler sur la cible.
L'inventaire statique du lot 1 ne montre aucun log de token dans `core/auth.py` ; aucun filtre
applicatif global n'était configuré dans le runtime audité avant A. Le profil est désormais
appliqué dans B sur la cible. Ne jamais ajouter l'URL ou le bearer aux messages d'erreur.
