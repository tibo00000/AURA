# SEC-003.3 — candidat autonome après réserves R1/R2

## Périmètre et décision

Le retour indépendant sur SEC-003.2 valide F1–F6 avec deux réserves localisées.
R1 est corrigé et testé ; R2 est neutralisé. Le candidat est construit et vérifié
localement, sans accès production, transfert VPS, migration ou déploiement.

**Préparation historique : GO revue du candidat. Activation ensuite autorisée et réalisée
le21 octobre2026 ; production rouverte après validation. Bilan actuel dans
[SEC-003.4](security-lot3-preflight.md). Image et sources figées inchangées.**
La coordination reste limitée à un seul processus mutateur ; les limites durables
et le contrat DELETE sont définis dans [jobs](../server/jobs.md), la provenance et
les règles fichiers dans [storage](../server/storage.md).

## Réserves et traitement

- **R1 confirmé avant correction :** `unlink` de l'audio personnel précédait le
  lien/copie. Une copie partielle en échec laisse un fichier dégradé et perd
  l'ancienne entrée. La propagation prépare maintenant un temporaire dans le même
  répertoire puis appelle `os.replace`, sous la garde de stockage existante.
  Échecs de copie et de remplacement préservent octets, inode et métadonnées.
  Les témoins hardlink restent intacts. Le fallback copie est aussi testé positivement.
- **R2, incohérence confirmée mais course non reproduite :** l'éviction utilisait
  une garde par SHA-256, les workers une garde par ID piste. Aucun appel de production
  n'a été trouvé dans le dépôt. `cleanup_orphaned_cache` retourne désormais zéro
  sans aucun accès aux fichiers ou verrous. Aucune option ne permet sa réactivation.
  Cette neutralisation évite d'introduire une coordination supplémentaire pour une
  fonction inactive ; elle implique une rétention du cache sans éviction automatique.
- Le test DELETE personnel/propagation utilise une barrière au remplacement atomique,
  au lieu de l'unlink supprimé. Les deux opérations restent sérialisées.

## Identité figée

| Élément | Identité / empreinte SHA-256 |
| --- | --- |
| Image autonome Docker / manifest OCI, Linux AMD64 | `sha256:cc5fcd84e4c5c58ff095b9f6f290e34f95eddb0d26b5fa36f1998eee73fb60ec` |
| Tag local | `aura-security-lot3:sec0033-20261020-amd64` |
| Configuration OCI | `44d35a2bf52a08ae95ee871fc025b2f4e7569bd4de02342298a1de4c1a701607` |
| Archive image `security-lot3-rc-amd64.tar.gz` | `8e1f7ee55857de3f206d95f5350e32385ae0400ea0ebe7b15f207d98028c8f9e` |
| Archive contexte `security-lot3-build-context.tar.gz` | `e8e77a9d1b0bc0e6fc990898565707150b63bc50470e7bdf842dc0b15b93a58a` |
| Manifeste des sources | `ba6f6b2b6cab01a4bf1a7fae798cfb807f448be995a3f75221d309a995e004aa` |
| Migration lot2 inchangée `20261017200822_secure_user_data_isolation.sql` | `ffa144f458f65cd2a0ba000ee07fbbeb6faa352cf9d6316f7c9e0b40a806afa4` |
| Base lot2 conservée intacte | `sha256:3dedda8e3518d5f12ffc65f7765d92310afa46d83b63e006bbec61fdbd619bb2` |

Livraison locale : `scratch/sec0033/delivery/`, manifeste `candidate-manifest.json`.
Le HEAD reste `16332183c7f453c27ce840658f862b6183947208` ; l'arbre lot2/lot3 est non
commité. Le patch complémentaire R1/R2 part de l'état SEC-003.2 dont le patch porte
l'empreinte `b4751045575cd3245601e781173b852baedfa37e18979240053ff5e34066eacb`.
Il ne s'applique pas directement au HEAD lot1. Le contexte complet capture le code
runtime final, y compris les fichiers non suivis, sans dépendre de ces commits absents.

## Construction et contrôles de contenu

La recette `server/ops/sec0033/image.Dockerfile` utilise exclusivement la base lot2
locale vérifiée. Aucun apt/pip/Deno ni package n'a été téléchargé ou mis à jour.
Les sources et tests hérités visibles sont retirés avant copie du nouveau snapshot.
111 fichiers de contexte sont inventoriés ; 110 sont vérifiés dans l'image, la recette
étant hashée séparément. Aucun bind de code, scripts ou tests n'est nécessaire.

Python3.12.15, Supabase/PostgREST/Auth2.33.0, PyJWT2.15.1, Uvicorn0.54.0,
cryptography50.0.2, FFmpeg7.1.5 et Deno2.9.7. Toutes les distributions Python
correspondent exactement au lock lot2 ; `pip check` et les contraintes pyproject passent.
Le lock complet est livré avec le candidat.

Les 29 couches de l'archive ont été inspectées : aucun `.env` réel/cookies/credentials,
ni motif de clé privée, clé serveur ou JWT dans les sources applicatives/validation.
Les modèles d'environnement sont synthétiques. Ce contrôle par allowlist et motifs
ne remplace pas une preuve exhaustive d'absence de tout secret possible.

Construction locale exécutée, à reproduire seulement depuis une base vérifiée :

```powershell
python -B server/ops/sec0033/freeze_candidate.py --output <contexte-neuf>
docker image inspect aura-security-lot2:normalized-sec0024-amd64 --format '{{.Id}} {{.Os}}/{{.Architecture}}'
docker buildx build --no-cache --platform linux/amd64 --network none --pull=false --provenance=false --output type=docker,dest=<image-neuve.tar>,rewrite-timestamp=true --build-arg SOURCE_DATE_EPOCH=1792271645 --build-arg SOURCE_MANIFEST_SHA256=ba6f6b2b6cab01a4bf1a7fae798cfb807f448be995a3f75221d309a995e004aa --build-arg BASE_IMAGE=aura-security-lot2:normalized-sec0024-amd64 -t aura-security-lot3:sec0033-20261020-amd64 <contexte-neuf>
docker load --input <image-neuve.tar>
```

La recette/contexte/base sont figés. Une seule construction de cette image a été
exécutée : l'égalité bit à bit après une seconde reconstruction n'est pas revendiquée.
Les commandes complètes réellement utilisées et leurs sorties sont dans la livraison.

## Tests réellement exécutés

| Contrôle | Résultat |
| --- | --- |
| R1/R2 sur base SEC-003.2 | 4 échecs attendus, 1 contrôle positif réussi |
| Tests ciblés après correction | 130 réussis, zéro ignoré, 27,38s |
| Suite backend directement dans l'image autonome, sans montage | 329 réussis, zéro ignoré, 56,06s |
| Vérificateur des fichiers/imports/dépendances embarqués | 110 empreintes conformes, import depuis `/app/app`, absence de vieux paquet applicatif, pip check réussi |
| Démarrage avec le vrai CMD dans un conteneur jetable | 1 processus Uvicorn, `/health`200, `/downloads` anonyme401, `/test`404, OpenAPI DELETE204 vide |
| Protections de l'image | debug/recovery/backfill false, aucun port publié, aucun montage, réseau none |
| Logs du vrai CMD | accès structurés présents, marqueur synthétique en query absent des logs |
| Sondes lot1 embarquées | authentification synthétique200, invalide/expiré401, streaming200, Range206, octets conformes |

Un warning Starlette/httpx TestClient préexistant est émis. Les sondes historiques
incluent un témoin Uvicorn brut montrant la fuite du marqueur dans ses access logs ;
ce témoin synthétique n'est pas le CMD sécurisé du candidat. Aucun JWT réel n'est utilisé.
Le service local temporaire a été arrêté et supprimé après les vérifications.

Les contrôles PostgreSQL du lot2/SEC-003.2 (107 assertions et3 préconditions) restent
les preuves précédentes ; ils n'ont pas été relancés pour R1/R2, qui ne changent aucun SQL.
La suite embarquée inclut les tests API/SDK A/B, mais aucune chaîne Supabase réelle
ni application Android/Desktop/Web n'a été retestée dans cette préparation.

## Gates avant toute activation

Cette préparation ne prouve aucune configuration déployée. Aucun accès VPS/Supabase
production n'a été réalisé. Avant une future autorisation de déploiement :

1. Autoriser séparément le préflight distant nécessaire ; vérifier Compose/Caddy,
   protections lot1, image lot2 active et absence de code monté ou commande surchargeant
   le mode mono-processus. Ne pas réactiver dev/recovery/backfill.
2. Prévoir une maintenance et l'arrêt effectif de l'ancien backend ; confirmer la fin
   de ses workers natifs avant toute bascule. Le test local ne prouve pas leur arrêt
   sur le VPS. Si l'arrêt ne se termine pas, prolonger la maintenance et demander une
   décision humaine ; ne pas supprimer leur contexte par simple expiration d'un délai.
3. Vérifier empreinte d'archive et image importée puis les validations Auth/streaming/API
   du candidat dans le périmètre futur autorisé. Réouverture soumise aux contrôles critiques.

Aucune migration SQL, modification de secret ou mise à jour client n'est requise par
R1/R2. L'activation requiert un redéploiement backend explicitement autorisé ; aucune
commande de transfert, arrêt de production, reload ou déploiement n'a été exécutée.
