# Hosting Strategy

## Objectif
Definir la strategie d'hebergement requise avant l'activation de la recherche online v1.

## Regle produit
- `Search` online v1 depend d'un backend AURA joignable sans latence de reveil perceptible.
- Un backend qui spin down et se reveille a la demande n'est pas acceptable pour la recherche online produit.

## Decision d'architecture
- La recherche online v1 reste backend only.
- Le backend doit donc etre heberge sur une cible always-on avant `SRV-002` et `AND-005`.

## Cible retenue actuelle
- cible retenue : Contabo Cloud VPS 10
- specification retenue : `4 vCPU`, `8 GB RAM`, `75 GB NVMe`
- image recommandee : `Ubuntu 24.04 LTS`
- deploiement initial : Docker sur le VPS
- reverse proxy recommande ensuite : Caddy

## Options retenues

### Option A - Render sans spin-down
- acceptable si le plan retenu garantit une disponibilite continue
- permet de garder l'infra actuelle
- pas de changement de contrat API

### Option B - VPS / serveur dedie
- option recommandee si :
  - Render reste limite en RAM
  - des jobs headless ou anti-bot reviennent dans le scope
  - un controle plus fin du runtime devient necessaire
- Hetzner est une cible plausible

## Recommandation de capacite
- pour une API seule : une machine modeste peut suffire
- si des workers headless, anti-bot ou jobs lourds sont prevus, viser plutot `8 GB RAM` minimum

## Elements a figer avant production
- reverse proxy
- TLS
- variables d'environnement
- strategie de deploiement Docker
- logs
- supervision minimale
- sauvegardes et redemarrage

## Regles
- ne pas deplacer Deezer dans Android pour contourner un probleme de spin-down
- ne pas changer les contrats API tant que l'hebergement ne change pas les capacites metier
- garder l'application codee contre une URL de backend abstraite

## Code Mapping
- `render.yaml` : blueprint actuel Render
- `infra/docker/server.Dockerfile` : image backend deployable
- `infra/docker-compose.vps.yml` : deploiement rapide backend sur VPS
- `infra/docker-compose.vps.caddy.yml` : ajout du reverse proxy TLS sur VPS
- `infra/vps/README.md` : procedure de bootstrap et deploiement VPS
- `server/app/config.py` : variables d'environnement utilisees par l'API
