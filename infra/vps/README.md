# VPS Deployment

## Objectif
Fournir un chemin de deploiement simple pour un VPS always-on, avec une premiere mise en ligne rapide de l'API puis un passage optionnel a un reverse proxy TLS.

## Cible choisie
- fournisseur retenu : Contabo
- machine retenue : Cloud VPS 10
- image recommandee : Ubuntu 24.04 LTS

## Strategie de deploiement
- etape 1 : deployer l'API FastAPI en Docker sur le port `8000`
- etape 2 : ajouter Caddy quand le domaine API est pret

## Variables necessaires dans `server/.env`
- `APP_NAME=AURA API`
- `APP_ENV=production`
- `APP_DEBUG=false`
- `APP_HOST=0.0.0.0`
- `APP_PORT=8000`
- `DATABASE_URL=...`
- `SUPABASE_URL=...`
- `SUPABASE_ANON_KEY=...`
- `SUPABASE_SERVICE_ROLE_KEY=...`
- `QDRANT_URL=...`
- `QDRANT_API_KEY=...`
- `LOG_LEVEL=INFO`

## Bootstrap Ubuntu
Executer sur le VPS :

```bash
apt update && apt upgrade -y
apt install -y ca-certificates curl gnupg git ufw

install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc

echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu \
$(. /etc/os-release && echo \"$VERSION_CODENAME\") stable" | tee /etc/apt/sources.list.d/docker.list > /dev/null

apt update
apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
systemctl enable docker
systemctl start docker

ufw allow OpenSSH
ufw allow 8000/tcp
ufw --force enable
```

## Deploiement rapide sans domaine
Depuis le VPS :

```bash
cd /opt
git clone <YOUR_REPO_URL> aura
cd /opt/aura
cp server/.env.example server/.env
nano server/.env
docker compose -f infra/docker-compose.vps.yml up -d --build
docker compose -f infra/docker-compose.vps.yml ps
curl http://127.0.0.1:8000/health
```

## Test externe rapide
- verifier `http://<VPS_IP>:8000/health`

## Passage au domaine et TLS
Quand le sous-domaine API pointe vers le VPS :

```bash
cd /opt/aura
mkdir -p infra/caddy
cp infra/caddy/Caddyfile.example infra/caddy/Caddyfile
nano infra/caddy/Caddyfile
ufw allow 80/tcp
ufw allow 443/tcp
docker compose -f infra/docker-compose.vps.yml -f infra/docker-compose.vps.caddy.yml up -d
```

## Verification TLS
- verifier `https://<your-domain>/health`

## Regles
- tant que Caddy n'est pas en place, l'API reste exposee sur `:8000`
- une fois le domaine pret, preferer passer tout le trafic par Caddy
- ne jamais committer `server/.env` avec les vraies valeurs

## Fichiers lies
- `infra/docker-compose.vps.yml`
- `infra/docker-compose.vps.caddy.yml`
- `infra/caddy/Caddyfile.example`
- `infra/docker/server.Dockerfile`
- `server/.env.example`
