# Jobs Asynchrones

## Types de jobs
- telechargement audio futur
- enrichissement de metadonnees
- generation d'artefacts de pochettes ou caches
- maintenance technique

## Cycle de vie
- `queued`
- `running`
- `succeeded`
- `failed`
- `cancelled`

## Regles
- Chaque job porte un identifiant stable.
- Les progressions doivent etre lisibles depuis Android et via MCP.
- Un echec doit exposer un code et un message diagnostic.

## Integration produit
- L'ecran `Downloads` consomme directement cet etat.
- Les logs applicatifs lient chaque job a ses erreurs et a son provider source.
