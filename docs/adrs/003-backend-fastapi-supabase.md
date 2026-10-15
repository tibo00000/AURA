# ADR 003 - Backend FastAPI, Supabase et jobs

## Statut
Accepte

## Contexte
Le projet doit rester peu couteux, personnel et modulaire. Il repose sur Supabase, Docker et un hebergement always-on. La documentation doit privilegier des contrats machines simples a lire.

## Decision
- L'API serveur de reference est ecrite en FastAPI.
- Supabase est la source de verite cloud pour Postgres, Auth optionnelle et Storage.
- Les operations couteuses comme les telechargements et preparations de flux passent par des jobs asynchrones.

## Consequences
- Les schemas JSON et les DTOs doivent rester stricts et explicites.
- Le serveur doit separer clairement donnees transactionnelles et objets binaires.
- Les integrations externes sont encapsulees dans des adaptateurs de providers.
