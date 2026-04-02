# ADR 002 - Android Native Client

## Statut
Accepte

## Contexte
AURA cible d'abord un usage personnel sur mobile avec fortes exigences sur la lecture audio, le mode hors-ligne et la fluidite UI. Le prototype precedent a deja valide l'orientation Android.

## Decision
- Le client officiel v1 est une application Android native.
- La stack UI est Kotlin + Jetpack Compose + Material 3.
- Le moteur de lecture repose sur Media3/ExoPlayer.
- L'architecture client suit MVVM avec etat observable via StateFlow.

## Consequences
- La documentation fonctionnelle de reference est ecrite d'abord pour Android.
- Les abstractions metier doivent rester suffisamment neutres pour autoriser plus tard un autre client.
- Les composants UI, le player et la navigation sont documentes comme surfaces Android de reference.
