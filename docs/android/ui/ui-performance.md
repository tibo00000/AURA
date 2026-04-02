# Optimisation Des Performances UI

## Objectif
Maintenir une interface Compose fluide avec un player persistant et plusieurs listes riches.

## Listes
- Fournir une cle unique stable pour chaque item de `LazyColumn`.
- Utiliser `contentType` quand plusieurs types de lignes coexistent.
- Eviter les calculs couteux dans les items de liste.

## Etat
- Encapsuler les lectures d'etats rapides dans `derivedStateOf`.
- Calculer tri, filtrage et sections hors des cellules UI.
- Exposer des etats ecran deja pretransformes depuis le ViewModel.

## Media3
- Garder une instance unique de player.
- Le rendu video n'est pas un besoin v1, mais les surfaces audio et notifications doivent etre gerees par une session unique.
- Le moteur de queue est separe du composant visuel afin de faciliter les tests.

## Images
- Charger les pochettes via Coil avec dimensions adaptees.
- Utiliser cache memoire et disque.

## Scroll et overlays
- Le mini-player et la bottom bar imposent un padding bas explicite.
- Les drag handles de queue ne doivent pas provoquer de recompositions globales.
