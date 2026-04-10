# ADR 006 - Online Search Backend Only

## Statut
Accepte

## Contexte
La recherche online v1 d'AURA doit interroger Deezer tout en gardant un modele metier stable, un mapping centralise et une trajectoire compatible avec l'evolution future des providers.

## Decision
- L'application Android ne doit pas appeler Deezer directement pour la recherche online v1.
- La recherche online v1 passe exclusivement par le backend AURA.
- L'adaptateur Deezer reste encapsule cote serveur.
- Le backend doit etre traite comme une brique always-on avant le branchement de `SRV-002` et `AND-005`.

## Consequences
- Android reste responsable :
  - des suggestions locales pendant la saisie
  - de la fusion local + online apres validation
- Le backend reste responsable :
  - de la recherche online only
  - de l'adaptation provider Deezer
  - du mapping vers le domaine AURA
- Un backend sujet au spin-down est un blocage UX produit pour `Search`.

## Alternatives ecartees
- appels Deezer directs depuis Android
  - couplage plus fort au provider
  - logique provider dupliquee dans l'app
  - plus de dette de securite et de mapping
- fallback hybride backend puis Deezer direct
  - disponibilite meilleure
  - complexite d'architecture trop elevee pour v1
