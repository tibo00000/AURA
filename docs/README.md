# AURA Documentation Map

Ce dossier contient la source de verite documentaire du projet AURA.

## Conventions
- La documentation de reference vit en Markdown.
- `llms.txt` est l'index semantique de haut niveau.
- `llms-full.txt` est la concatenation canonique des documents ci-dessous.
- Chaque fichier doit couvrir un seul sujet et annoncer explicitement ses dependances.
- Les noms de fichiers doivent rester stables afin de limiter les changements dans l'index semantique.
- Les regles de redaction et de maintenance sont definies dans `docs/documentation/style-guide.md`.

## Ordre canonique de concatenation
1. `AGENTS.md`
2. `BUILD.md`
3. `docs/adrs/001-machine-friendly-docs.md`
4. `docs/adrs/002-android-native-client.md`
5. `docs/adrs/003-backend-fastapi-supabase.md`
6. `docs/adrs/004-provider-adapter-strategy.md`
7. `docs/adrs/005-mcp-bridge.md`
8. `docs/adrs/006-online-search-backend-only.md`
9. `docs/adrs/007-desktop-client-cmp.md`
10. `docs/architecture/engineering-rules.md`
11. `docs/documentation/style-guide.md`
12. `docs/product/vision.md`
13. `docs/product/navigation.md`
14. `docs/product/user-flows.md`
15. `docs/domain/entities.md`
16. `docs/domain/data-relationships.md`
17. `docs/domain/playback-model.md`
18. `docs/domain/playback-user-flows.md`
19. `docs/domain/provider-architecture.md`
20. `docs/android/app-architecture.md`
21. `docs/android/local-persistence.md`
22. `docs/android/metadata-editor.md`
22. `docs/android/room-schema.md`
23. `docs/android/room-relationships.md`
24. `docs/android/navigation.md`
25. `docs/android/ui/design-system.md`
26. `docs/android/ui/components.md`
27. `docs/android/ui/component-states.md`
28. `docs/android/ui/screen-composition.md`
29. `docs/android/ui/ui-performance.md`
30. `docs/android/player/architecture.md`
31. `docs/android/player/queue-rules.md`
32. `docs/android/player/states-and-events.md`
33. `docs/android/screens/home.md`
34. `docs/android/screens/home-layout.md`
35. `docs/android/screens/search.md`
36. `docs/android/screens/search-layout.md`
37. `docs/android/screens/library.md`
38. `docs/android/screens/library-layout.md`
39. `docs/android/screens/favorites-layout.md`
40. `docs/android/screens/playlists.md`
41. `docs/android/screens/playlists-layout.md`
42. `docs/android/screens/player.md`
43. `docs/android/screens/player-layout.md`
44. `docs/android/screens/artist.md`
45. `docs/android/screens/artist-layout.md`
46. `docs/android/screens/album.md`
47. `docs/android/screens/album-layout.md`
48. `docs/android/screens/downloads.md`
49. `docs/android/screens/downloads-layout.md`
50. `docs/android/screens/settings.md`
51. `docs/android/screens/cloud-sync.md`
52. `docs/android/screens/cloud-sync-layout.md`
53. `docs/desktop/app-architecture.md`
54. `docs/server/architecture.md`
55. `docs/server/api-contract.md`
56. `docs/server/sync-conflict-resolution.md`
57. `docs/server/sync-batch-api.md`
58. `docs/server/api-sync-flows.md`
59. `docs/server/jobs.md`
60. `docs/server/database-postgres.md`
61. `docs/server/postgres-relationships.md`
62. `docs/server/storage.md`
63. `docs/server/providers/deezer.md`
64. `docs/server/providers/streaming-bridge.md`
65. `docs/server/security-and-secrets.md`
    - `docs/server/security-lot2-isolation.md` : matrice avant/après, architecture JWT/RLS, migration préparée et limites de validation du lot 2.
    - `docs/ops/security-lot2-validation.md` : SEC-002.1/SEC-002.2, corrections REST/RPC, inventaire et gates de maintenance.
    - `docs/ops/security-lot2-isolated-integration.md` : SEC-002.3, projet Supabase dédié, backend HTTPS local, scripts et autorisations d'intégration A/B réelle.
    - `docs/ops/security-lot2-deployment.md` : SEC-002.4, candidat autonome figé, preuves et runbook final M0–M8, sauvegarde/restauration et autorisations distinctes.
    - `docs/ops/security-lot2-recovery.md` : SEC-002.5, une sauvegarde applicative chiffrée/restaurée, identités et dépendances nécessaires, heure T et perte ultérieure acceptée, autorisation M2-S.
66. `docs/mcp/overview.md`
67. `docs/mcp/resources.md`
68. `docs/mcp/use-cases.md`
69. `docs/ops/logging-observability.md`
70. `docs/ops/env-vars.md`
71. `docs/ops/hosting-strategy.md`
72. `docs/ops/security-lot1-intervention.md`
73. `docs/ops/security-lot3-candidate.md`
74. `docs/ops/security-lot3-preflight.md`
75. `docs/ops/ota-deployment-guide.md`
76. `docs/ops/security-lot5-local-validation.md`
77. `docs/ops/security-lot5-candidate.md`
78. `docs/ops/security-lot5-window-decisions.md`

## Rythme de mise a jour
- Toute creation ou suppression d'un document impose une mise a jour de `llms.txt`.
- Toute modification de structure impose une regeneration de `llms-full.txt`.
- Les documents produit et domaine doivent etre valides avant de documenter une implementation detaillee.
- Toute modification documentaire doit respecter `docs/documentation/style-guide.md`.

## Outils de verification

- `server/scripts/verify_security_lot1_local.py` : sonde hors ligne Auth, streaming synthétique et journaux Uvicorn ; procédure et limites dans `docs/server/security-and-secrets.md` et `docs/ops/logging-observability.md`.

- `server/scripts/test_user_isolation_postgres.py --download-cancellation` : assertions PostgreSQL jetables des lots2/3, sans réseau distant ni données réelles ; contrat et limites dans `docs/server/jobs.md`.

- `server/tests/test_sec0032_regressions.py`, `test_download_admission_recovery.py` et `test_download_cache_provenance.py` : reproductions F1–F6, supervision, concurrence et provenance du cache ; invariants et limites dans `docs/server/jobs.md` et `docs/server/storage.md`.

- `server/ops/sec0033/` : contexte allowlisté, recette hors réseau et vérification embarquée du candidat autonome SEC-003 ; contrôles R1/R2 dans `server/tests/test_sec0033_robustness.py`.

- `server/ops/sec0034/` : préflight stdin à sortie expurgée et comptages SQL READ ONLY, sans exécution distante implicite ; tests préparatoires dans `server/ops_tests/test_sec0034_preflight.py`.

- `server/ops/sec0052/` : contrôles de sauvegarde et de restauration, historique SQLite et validation synthétique du backend. La [procédure de déploiement](ops/security-lot5-candidate.md) indique les configurations et commandes à utiliser.
