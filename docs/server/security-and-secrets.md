# Security And Secrets

## Principes
- aucun secret en dur
- separation claire entre variables Android et serveur
- moindre privilege pour toutes les integrations

## Variables a prevoir
- URL et cle publique Supabase
- secrets serveur Supabase
- URL Qdrant et jeton associe
- cles provider externes si necessaires
- configuration des workers et jobs

## Regles
- documenter toute variable dans un exemple dedie
- masquer les secrets dans les logs et via MCP
- segmenter les environnements local, staging personnel et production personnelle
