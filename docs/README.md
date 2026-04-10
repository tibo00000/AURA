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
5. `docs/adrs/003-backend-fastapi-supabase-qdrant.md`
6. `docs/adrs/004-provider-adapter-strategy.md`
7. `docs/adrs/005-mcp-bridge.md`
8. `docs/adrs/006-online-search-backend-only.md`
9. `docs/documentation/style-guide.md`
10. `docs/product/vision.md`
11. `docs/product/navigation.md`
12. `docs/product/user-flows.md`
13. `docs/domain/entities.md`
14. `docs/domain/data-relationships.md`
15. `docs/domain/playback-model.md`
16. `docs/domain/playback-user-flows.md`
17. `docs/domain/provider-architecture.md`
18. `docs/android/app-architecture.md`
19. `docs/android/local-persistence.md`
20. `docs/android/room-schema.md`
21. `docs/android/room-relationships.md`
22. `docs/android/navigation.md`
23. `docs/android/ui/design-system.md`
24. `docs/android/ui/components.md`
25. `docs/android/ui/component-states.md`
26. `docs/android/ui/screen-composition.md`
27. `docs/android/ui/ui-performance.md`
28. `docs/android/player/architecture.md`
29. `docs/android/player/queue-rules.md`
30. `docs/android/player/states-and-events.md`
31. `docs/android/screens/home.md`
32. `docs/android/screens/search.md`
33. `docs/android/screens/library.md`
34. `docs/android/screens/playlists.md`
35. `docs/android/screens/player.md`
36. `docs/android/screens/player-layout.md`
37. `docs/android/screens/artist.md`
38. `docs/android/screens/album.md`
39. `docs/android/screens/downloads.md`
40. `docs/android/screens/settings.md`
41. `docs/server/architecture.md`
42. `docs/server/api-contract.md`
43. `docs/server/sync-conflict-resolution.md`
44. `docs/server/sync-batch-api.md`
45. `docs/server/api-sync-flows.md`
46. `docs/server/jobs.md`
47. `docs/server/database-postgres.md`
48. `docs/server/postgres-relationships.md`
49. `docs/server/vector-search-qdrant.md`
50. `docs/server/storage.md`
51. `docs/server/providers/deezer.md`
52. `docs/server/providers/streaming-bridge.md`
53. `docs/server/security-and-secrets.md`
54. `docs/mcp/overview.md`
55. `docs/mcp/resources.md`
56. `docs/mcp/use-cases.md`
57. `docs/ops/logging-observability.md`
58. `docs/ops/env-vars.md`
59. `docs/ops/hosting-strategy.md`

## Rythme de mise a jour
- Toute creation ou suppression d'un document impose une mise a jour de `llms.txt`.
- Toute modification de structure impose une regeneration de `llms-full.txt`.
- Les documents produit et domaine doivent etre valides avant de documenter une implementation detaillee.
- Toute modification documentaire doit respecter `docs/documentation/style-guide.md`.
