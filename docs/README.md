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
62. `docs/server/vector-search-qdrant.md`
63. `docs/server/storage.md`
64. `docs/server/providers/deezer.md`
65. `docs/server/providers/streaming-bridge.md`
66. `docs/server/security-and-secrets.md`
67. `docs/mcp/overview.md`
68. `docs/mcp/resources.md`
69. `docs/mcp/use-cases.md`
70. `docs/ops/logging-observability.md`
71. `docs/ops/env-vars.md`
72. `docs/ops/hosting-strategy.md`

## Rythme de mise a jour
- Toute creation ou suppression d'un document impose une mise a jour de `llms.txt`.
- Toute modification de structure impose une regeneration de `llms-full.txt`.
- Les documents produit et domaine doivent etre valides avant de documenter une implementation detaillee.
- Toute modification documentaire doit respecter `docs/documentation/style-guide.md`.
