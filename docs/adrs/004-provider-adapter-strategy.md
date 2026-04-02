# ADR 004 - Provider Adapter Strategy

## Statut
Accepte

## Contexte
AURA doit supporter une recherche en ligne des titres, artistes et albums, avec Deezer comme provider v1 probable. A plus long terme, un bridge de streaming plus riche peut remplacer ou completer cette source.

## Decision
- Toute source externe passe derriere une interface d'adaptateur.
- Deezer est le provider de reference pour la recherche online v1.
- Les fonctionnalites de streaming futur ou de bridge Hi-Fi doivent s'integrer sans recoder le coeur du produit.

## Consequences
- Les objets metier ne doivent pas dependre directement du format natif d'un provider.
- Le serveur peut changer de provider sans refaire la documentation produit.
- Les limites juridiques, techniques et de quota d'un provider sont documentees dans son fichier dedie.
