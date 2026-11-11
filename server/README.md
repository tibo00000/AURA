# AURA Server

FastAPI backend skeleton for AURA.

## Local run
1. Create a virtual environment.
2. Install dependencies from `pyproject.toml`.
3. Copy `.env.example` to `.env` and fill the values you need.
4. Run `uvicorn app.main:app --reload`.

## First endpoint
- `GET /health`

## Private API authentication

Use a valid Supabase access token through `Authorization: Bearer <JWT>` or the audio
stream `token` query parameter. `SUPABASE_URL` must be the trusted HTTPS project URL.
ES256/RS256 keys are discovered from that project's public JWKS endpoint. Legacy HS256
tokens require the server-only `SUPABASE_JWT_SECRET`; a missing secret never enables
unsigned verification. The fixed owner UUID is not a credential, and `/test` routes
are not mounted. See `../docs/server/security-and-secrets.md` for the verification contract.

Deploy by rebuilding the backend image from `infra/docker/server.Dockerfile` so the
`PyJWT[crypto]` dependency is installed. Confirm access to the trusted HTTPS JWKS endpoint
and the signing mode of active sessions before switching traffic. An advertised ES256
key may still be a standby key; active HS256 sessions need their configured signing
secret or a refreshed session issued with an active asymmetric key. No database migration
or mandatory client release is required for this authentication boundary change.

