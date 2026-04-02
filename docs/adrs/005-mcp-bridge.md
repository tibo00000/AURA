# ADR 005 - MCP Bridge

## Statut
Accepte

## Contexte
Le projet veut qu'un agent IA puisse lire l'etat du systeme en temps reel sans contourner les limites du code source statique.

## Decision
- Un serveur MCP expose au minimum les logs applicatifs, les schemas de base et l'etat des jobs.
- Le MCP est en lecture seule pour la majorite des ressources.
- Les ressources exposees sont nommees de facon stable et documentees.

## Consequences
- Le serveur doit preparer des vues lisibles pour les jobs, erreurs et schemas.
- La doc MCP complete la doc de code et ne la remplace pas.
- Les secrets et donnees sensibles doivent etre filtres avant exposition.
