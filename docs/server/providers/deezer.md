# Deezer Provider

## Role
Fournisseur de recherche online v1 pour titres, artistes et albums.

## Capacites documentees
- rechercher des titres
- rechercher des artistes
- rechercher des albums
- recuperer des metadonnees publiques utiles a l'exploration

## Regles
- Deezer est encapsule derriere un adaptateur.
- Les objets renvoyes sont mappes vers le domaine interne.
- Les erreurs, quotas et indisponibilites restent locales a l'adaptateur.

## Limites
- Deezer ne doit pas dicter le modele metier AURA.
- Les actions futures de telechargement ou streaming dependront d'autres briques que de cette seule integration.
