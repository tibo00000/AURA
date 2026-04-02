# Vector Search And Qdrant

## Role
Qdrant stocke les vecteurs utilises pour les recommandations et recherches par similarite, ainsi qu'un payload de metadonnees associe a chaque vecteur.

## Etat actuel connu
- Le prototype dispose d'environ 2,2 millions de vecteurs populaires.
- L'embedding courant vit dans un espace de 32 dimensions.
- La similarite utilise le cosinus.
- La recommandation prototype moyenne actuellement les vecteurs d'une playlist.

## Payload de metadonnees associe au vecteur
- Chaque point Qdrant peut embarquer un payload de metadonnees directement exploitable.
- Exemple de payload actuel :

```json
{
  "track_name": "The Giver",
  "artist_name": "Chappell Roan",
  "genres": "Inconnu",
  "popularity": 89,
  "duration_ms": 202768,
  "explicit": false
}
```

## Usage attendu du payload
- aider a filtrer, scorer ou expliquer un resultat de recommandation
- enrichir la transformation d'un resultat Qdrant vers une entite `Track`
- eviter de dependre uniquement de l'identifiant du vecteur pour comprendre un resultat

## Place dans v1
- Le moteur vectoriel est mentionne structurellement dans la documentation.
- Les details algorithmiques fins seront documentes plus tard quand les invariants seront stabilises.

## Contraintes
- Les identifiants pistes doivent etre alignes entre Qdrant et le domaine AURA.
- Les reponses de reco doivent pouvoir etre transformees en objets `Track` exploitables par Android.
- Le payload Qdrant ne remplace pas les tables transactionnelles de `Room` ou `Postgres`.
