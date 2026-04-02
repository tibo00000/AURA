# Server Architecture

## Objectif
Fournir une API et des jobs asynchrones pour la recherche, les metadonnees, la sync et les traitements lourds.

## Blocs
- FastAPI pour l'API HTTP
- services applicatifs pour recherche, bibliotheque, playlists et downloads
- adaptateurs providers
- Supabase pour Postgres, Auth optionnelle et Storage
- Qdrant pour les vecteurs
- workers de jobs asynchrones

## Flux
- L'app Android interroge l'API pour recherche online, sync et etat des jobs.
- Les services consultent Postgres pour les donnees transactionnelles.
- Les requetes de recommandation interrogent Qdrant.
- Les telechargements et preparations de flux sont delegues a des jobs.

## Contraintes
- garder des contrats JSON stables
- isoler les providers externes
- journaliser chaque job avec identifiant correlable
