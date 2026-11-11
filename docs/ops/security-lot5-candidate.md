# SEC-005.2 — Construire et déployer le backend sécurisé

SEC-005.2 est en production depuis le 11 novembre 2026. Les protections des lots 1 à 3 restent actives. Le lot 5 limite les uploads privés, conserve les remplacements partagés explicites et journalise leur résultat dans SQLite. [Validation de la fenêtre](security-lot5-window-decisions.md). [Contrat de stockage](../server/storage.md).

## Sources et image

- Image Linux AMD64 déployée : `sha256:cd2a68b333fcafd5753a3654d34be12a5dc5a492d11f0469ffb9446d7d824e91`.
- Archive locale `security-lot5-rc-amd64.tar.gz`, SHA-256 `d59d8b04d3fcf23eee0ebf2fa4a320353f745901bb99c7c2c4214e19ec316669`.
- Le candidat historique a été construit depuis le runtime SEC-003.3 et les patches validés SEC-005 et SEC-005.1. Le contexte gelé et la recette exacte sont conservés hors Git dans `scratch/sec0052/`. La recette utilise [image.Dockerfile](../../server/ops/sec0052/image.Dockerfile) et le [vérificateur embarqué](../../server/ops/sec0052/verify_image.py).
- Pour une future version, partir du commit explicitement retenu et du [Dockerfile du backend](../../infra/docker/server.Dockerfile). Pour le développement local, voir [server/README.md](../../server/README.md). Construire Linux AMD64, relever l’ID exact de l’image et valider depuis l’image sans montage du code. Vérifier Python 3.12, le SDK, le manifeste des sources et l’absence de secrets. Un nouveau build a sa propre identité. Les migrations PostgreSQL restent dans `supabase/migrations/` et suivent la [procédure Supabase](security-lot2-deployment.md).

## Préparer et exécuter une fenêtre autorisée

| Phase | Action et critère pour poursuivre |
| --- | --- |
| P | Contrôler ID du daemon, image et santé, projection Compose, deux SSH indépendants, TLS, ports directs fermés, absence de jobs actifs et autres writers. Dev arrêté avec `restart=no`, à revérifier après toute recréation. Sorties Docker limitées à des champs expurgés. Vérifier volume, inodes, marge cache, capacité de restauration et délais avant de fermer le service. |
| T | Si l’image n’est pas déjà importée, transférer l’archive approuvée dans un staging privé neuf, vérifier son SHA-256 aux deux extrémités, charger uniquement cette image et vérifier ID/plateforme. |
| M | Valider puis recharger le [Caddyfile de maintenance](../../infra/security-lot5/Caddyfile.maintenance). Vérifier le 503 depuis l’extérieur. Drainer et arrêter gracieusement le seul backend. Confirmer exit 0, zéro job actif et zéro writer du volume. |
| S | Créer une sauvegarde chiffrée du volume arrêté et prouver sa restauration hors production. Confirmer périmètre, destination, durée de conservation et clé de secours avant l’export. |
| D | Vérifier la projection avec l’[override de l’image](../../infra/security-lot5/compose.sec0052.json). Recréer seulement `aura-api` et vérifier ID, santé, un worker et un seul writer. |
| V | Valider Auth/refresh, isolation, streaming et Range, uploads, DELETE et propagation A/B avec les seuls comptes et pistes synthétiques autorisés. Vérifier l’intégrité SQLite et le nettoyage borné. |
| R | Valider puis recharger le [Caddyfile final](../../infra/security-lot5/Caddyfile.final). Vérifier santé et Auth HTTPS, streaming 200/206, limite multipart 51 Mio, dev en 503, logs expurgés et ports directs fermés. |

Chaque phase doit être couverte par l’autorisation de la fenêtre. Garder les identités et les paramètres de l’environnement dans la fiche opérateur. Contrôler la marge de stockage `max(10 Gio, 10 %)`, plus les écritures en cours. Vérifier que le Compose effectif garde le volume audio persistant sous `/app/downloads`, `SYNC_FILES_DIR` dans ce volume, le backend sans port public ni montage de code, debug/recovery/backfill désactivés, les logs structurés avec rotation et l’unique worker Uvicorn. Le conteneur dev reste arrêté. Le fichier `infra/security-lot1/Caddyfile.release` sert de référence antérieure à la limite multipart.

Pour Caddy, sauvegarder le fichier actif et identifier son montage avant modification. Si le fichier lui-même est monté, conserver son inode lors de l’écriture. Valider les candidats avec le même binaire Caddy que la cible, puis comparer le contenu adapté avec la configuration active après reload. Pour la bascule, le wrapper approuvé doit rendre les fichiers Compose de production et l’override doit être le dernier fichier :

```bash
"$WRAPPER" -f "$STAGE/compose.sec0052.json" config --quiet
"$WRAPPER" -f "$STAGE/compose.sec0052.json" up -d --no-deps --no-build --pull never --force-recreate aura-api
```

Comparer en mémoire les projections avec et sans override. Seuls l’image, la politique de pull et la commande API peuvent différer. Conserver les paramètres de réseau, volume et environnement approuvés.

## Sauvegarde et récupération

Confirmer zéro writer et absence de sous-montage. [backup_compatibility.py](../../server/ops/sec0052/backup_compatibility.py) vérifie les liens et les types de fichiers avec `--root "$AUDIO_ROOT"` sur Linux. Rejeter toute incompatibilité avant archive. Le volume inclut audio, métadonnées, cache et historique SQLite, avec les droits, xattrs et groupes de hardlinks. PostgreSQL/Auth Supabase suit sa [procédure distincte](security-lot2-recovery.md).

- Utiliser GNU tar et age. Dans un répertoire privé neuf (0700), noter le début et la fin de l’export. Écrire une archive chiffrée partielle (0600) via le pipeline ci-dessous avec `pipefail`. Chiffrer directement les deux manifestes produits par `storage_manifest.py manifest "$AUDIO_ROOT"`, l’un avant et l’autre après l’archive. La clé privée age et son secours restent hors VPS.
- Vérifier l’empreinte du ciphertext. Depuis un poste chiffré et un volume Docker neuf isolé, lire deux fois le même flux SSH. [stream_pipeline.py](../../server/ops/sec0052/stream_pipeline.py) contrôle le SHA-256 et les trois codes retour. Déchiffrer localement vers `check_tar.py` lors de la première lecture, puis vers GNU tar avec `--acls --xattrs --xattrs-include=* --numeric-owner --same-owner --same-permissions -xpf -` lors de la seconde. Comparer la restauration avec chacun des manifestes via `storage_manifest.py verify /restore`.
- Après contrôle intégral, finaliser l’archive par renommage et enlever uniquement le conteneur et le volume temporaires identifiés. Une interruption ou une divergence laisse le fichier partiel hors du jeu de sauvegardes utilisables. Consigner l’heure T, la rétention et le résultat de restauration. Une copie VPS seule ne couvre pas la perte du VPS.
- Pour sauvegarder **seulement** l’historique actif, [backup_history.py](../../server/ops/sec0052/backup_history.py) utilise l’API SQLite backup et inclut les transactions validées dans le WAL. La destination doit être neuve, privée et sur stockage chiffré.

```bash
set -o pipefail
umask 077
( set -o noclobber
  tar --format=pax --sparse --acls --xattrs --numeric-owner --one-file-system \
      -C "$AUDIO_ROOT" -cpf - . | age -r "$AGE_RECIPIENT" > "$PARTIAL"
)
```

En cas d’échec critique après M, conserver ou rétablir le 503 et faire décider de la suite. Aucune restauration des données réelles ni retour automatique à une image précédente. Après D, préserver le journal SQLite pour toute décision de récupération. Un retour à une image antérieure ferait perdre la protection et la journalisation des nouveaux remplacements, même si le fichier du journal est conservé.

## Fixtures et contrôles

Le [runner V](../../server/ops/sec0052/validation_v.py) attend un plan privé lié à l’ID exact du candidat, un reçu de D et le reçu de la [sonde de collision](../../server/ops/sec0052/fixture_collision_probe.py). `--write-example` génère un plan sans comptes ni sources. Pour une nouvelle version, adapter et vérifier les cibles et le schéma du runner avant toute exécution. La sonde dérive les chemins depuis la configuration, examine les métadonnées seules et refuse les collisions. Transporter les requêtes via un tunnel SSH sur loopback, sans publication du port interne. Garder les credentials et réponses privées hors Git et hors journaux.

Pour SEC-005.2 : quatre admissions, quatre pistes, deux comptes, 160 Mio d’uploads et 120 requêtes dont 16 réservées au nettoyage. Tester les uploads personnels, l’annulation du job ordinaire, les références natives A/B, la seconde réassignation et la cohérence des empreintes audio et de l’historique. Ne modifier aucun job réel, cache global ni événement d’audit. Le backend limite le fichier à 50 Mio et renvoie 413, 429 ou 507 selon le cas. Les alertes de débit restent informatives. Le retry d’une réassignation sans source persistée renvoie 400. Utiliser resolve dans un état admissible avec une source explicite, ou lancer une nouvelle réassignation.

Les comptes rendus et scripts propres à la première fenêtre restent hors Git dans `scratch/`, dont `scratch/archive-security-2026-11-11/`. Les paramètres privés sont conservés séparément.
