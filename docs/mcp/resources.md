# MCP Resources

## Ressources minimales
- `logs/app/latest`
- `logs/jobs/latest`
- `db/schema/postgres`
- `db/schema/qdrant`
- `jobs/active`
- `jobs/history/{id}`

## Format attendu
- ressources texte ou JSON lisibles
- timestamps explicites
- identifiants de correlation pour relier logs et jobs

## Contraintes
- pas de secrets exposes
- pas d'operation destructive depuis les ressources de lecture
