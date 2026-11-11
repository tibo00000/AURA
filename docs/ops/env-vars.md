# Environment Variables Inventory

## Android
- `SUPABASE_URL`
- `SUPABASE_ANON_KEY`
- `API_BASE_URL`

## Serveur
- `SUPABASE_URL`
- `SUPABASE_ANON_KEY`
- `SUPABASE_SERVICE_ROLE_KEY`
- `SUPABASE_JWT_SECRET` : secret serveur facultatif, obligatoire pour accepter HS256 ; ES256/RS256 utilisent le JWKS HTTPS dérivé de `SUPABASE_URL`.
- `DATABASE_URL`
- `DEEZER_API_BASE_URL`
- `JOBS_BACKEND_URL`
- `APP_DEBUG` : false en production ; le Compose de confinement impose false sans modifier `.env`.
- `ENABLE_STARTUP_RECOVERY`, `ENABLE_CACHE_BACKFILL` : routines d'écriture au démarrage ; le
  confinement les suspend explicitement, leur reprise demande une décision séparée.
- `UPLOAD_MAX_FILE_BYTES` : SEC-005,50Mio par défaut, octets audio comptés pendant la réception.
  L'enveloppe multipart et la limite Caddy doivent autoriser1Mio de plus ; pas un quota total.
- `UPLOAD_MIN_FREE_BYTES` :10Gio par défaut ; `UPLOAD_MIN_FREE_RATIO` :0.10 par défaut.
  La réserve vaut le maximum des deux sur le volume réel, après réservations concurrentes.
  Exemple `server/.env.example`, décisions et limites dans `security-lot5-local-validation.md`.

## Sélecteurs Compose opérationnels

- `AURA_CONTAINMENT_IMAGE` : tag local dont l'ID doit correspondre à l'image de confinement
  approuvée ; aucun pull/build implicite.
- `AURA_SECURITY_IMAGE` : image locale vérifiée du lot 1, exigée pour le modèle release.

Ces deux sélecteurs ne sont pas des secrets ni des paramètres Supabase. Exemple :
`infra/security-lot1/.env.example`. Ne pas les ajouter à un rapport avec des credentials.

`SUPABASE_URL` doit désigner le projet de confiance en HTTPS. L'absence de secret HS256
n'active aucun fallback non vérifié. Voir `docs/server/security-and-secrets.md` pour le contrat
de vérification et le comportement en cas de panne JWKS.

## Regles
- toute nouvelle variable doit etre documentee ici et dans un fichier exemple
- les valeurs reelles ne doivent jamais apparaitre dans le depot
- l'application Android doit toujours consommer une URL de backend abstraite comme `API_BASE_URL`, sans dependre directement d'un provider online
