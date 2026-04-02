# Provider Architecture

## But
Permettre a AURA de combiner local, recherche online, telechargement futur et streaming futur sans coupler le domaine a un fournisseur externe unique.

## Couches
- `Domain layer` : entites metier stables.
- `Provider adapter` : transforme les formats Deezer ou autres vers les objets internes.
- `Application services` : orchestration recherche, download jobs, enrichissement des metadonnees.

## Provider v1
- Deezer sert de source de recherche online pour titres, artistes et albums.
- Les reponses Deezer sont mappees vers `ProviderTrack`, `Artist` et `Album`.
- Les quotas, indisponibilites et erreurs reseau restent confines a l'adaptateur.

## Evolutions prevues
- Un bridge de streaming ou Hi-Fi pourra implementer les memes interfaces.
- Le telechargement futur utilisera les resultats de provider comme point d'entree, pas comme modele final de stockage.
- En absence de reseau, seules les donnees locales et cachees restent visibles.
