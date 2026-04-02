# Storage Strategy

## Types d'objets
- pochettes mises en cache
- artefacts temporaires de telechargement
- fichiers audio derives futurs
- exports techniques ou journaux traces si necessaire

## Principes
- Le stockage long terme doit rester limite pour contenir les couts.
- Les fichiers temporaires doivent etre supprimables sans casser les donnees transactionnelles.
- Les references vers des objets binaires vivent en base, pas uniquement dans des conventions implicites.
