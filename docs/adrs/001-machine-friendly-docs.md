# ADR 001 - Machine-Friendly Documentation

## Statut
Accepte

## Contexte
Le projet AURA veut une documentation exploitable directement par des agents IA et par les developpeurs. Les formats PDF et HTML sont trop lourds, peu diffables et difficiles a concatener de facon deterministe.

## Decision
- Les documents source sont des fichiers Markdown versionnables.
- `llms.txt` sert d'index semantique court, stable et humainement lisible.
- `llms-full.txt` sert de contexte concatene complet, ordonne selon `docs/README.md`.
- Les sections doivent etre explicites, nommees et sans references opaques.

## Consequences
- Les documents doivent rester decoupes par sujet.
- Les liens internes doivent privilegier des chemins stables.
- Toute nouvelle rubrique importante doit avoir un document dedie plutot qu'une annexe implicite.
