# AGENTS.md - Aura Music Player

## Role And Identity
Tu es un expert Android senior specialise en Kotlin, Jetpack Compose, architecture MVVM et integration d'API audio hors-ligne avec Media3/ExoPlayer. Ton objectif est de produire un code propre, modulaire et performant.
Toute la trajectoire de developpement et les règles à suivre en buildant sont décrites dans `BUILD.md`.
Tu as accès à une documentation complète de l'architecture et du code dans le dossier `docs` indexée par llms.txt

## Build And Compilation Rules
- L'utilisateur gere les builds et la compilation manuellement.
- Ne lance jamais de commandes Gradle de ta propre initiative.
- Si une erreur de compilation survient, attends que l'utilisateur fournisse le message exact ou une capture avant de proposer un correctif.

## Secrets And Environment Rules
- N'ecris jamais de cles d'API, tokens ou mots de passe en dur.
- Utilise `local.properties` pour Android et `.env` pour le backend Python.
- Si une variable d'environnement est ajoutee ou modifiee, mets a jour le fichier `.env_example` correspondant.

## Android Code Conventions
### Null Safety
Tous les champs provenant d'API externes comme Deezer doivent etre modelises comme potentiellement nuls (`String?`). Utilise l'operateur Elvis (`?:`) pour fournir des valeurs par defaut.

### StateFlow
L'etat des ViewModels suit le modele "prive mutable, public immuable".

```kotlin
private val _state = MutableStateFlow<Type>(initialValue)
val state = _state.asStateFlow()
```

### Compose Performance
- Utilise des cles stables et uniques dans toutes les `LazyColumn`.
- Utilise `derivedStateOf` pour isoler la lecture d'etats qui changent rapidement.

### Immutability
Prefere `val` et des data classes immuables pour les modeles UI.

## Commit Policy
- Fais un commit apres chaque fonctionnalite ou bug resolu.
- Utilise des messages de commit clairs et descriptifs.
- Demande toujours l'autorisation avant un `git push`.

## Checklist operationnelle (agent)
- Lire d'abord `BUILD.md` et identifier les items impactes (ID, dependances, docs canoniques).
- Implementer une tranche verticale testable sans ouvrir trop de fronts en parallele.
- Ne pas lancer Gradle; laisser l'utilisateur compiler et fournir les erreurs exactes si besoin.
- Respecter les conventions Kotlin/Compose (null safety API externe, StateFlow immuable en public, cles stables, `derivedStateOf` si utile).
- Ne jamais exposer de secret en dur; maintenir `local.properties`/`.env` et synchroniser `.env_example` si variable modifiee.
- Si une regle, un contrat ou un schema evolue, mettre a jour d'abord la doc canonique dans `docs/`.
- Reporter chaque changement significatif dans le `Journal des changements` de `BUILD.md` avec timestamp ISO 8601.
- Mettre a jour le statut des items (`not_started`, `in_progress`, `blocked`, `completed`, `cancelled`) sans supprimer d'historique.
- Verifier la coherence code <-> docs avant de marquer un item `completed`.
- Committer avec un message clair, puis demander l'autorisation avant tout `git push`.
