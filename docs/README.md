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
2. `docs/adrs/001-machine-friendly-docs.md`
3. `docs/adrs/002-android-native-client.md`
4. `docs/adrs/003-backend-fastapi-supabase-qdrant.md`
5. `docs/adrs/004-provider-adapter-strategy.md`
6. `docs/adrs/005-mcp-bridge.md`
7. `docs/documentation/style-guide.md`
8. `docs/product/vision.md`
9. `docs/product/navigation.md`
10. `docs/product/user-flows.md`
11. `docs/domain/entities.md`
12. `docs/domain/data-relationships.md`
13. `docs/domain/playback-model.md`
14. `docs/domain/playback-user-flows.md`
15. `docs/domain/provider-architecture.md`
16. `docs/android/app-architecture.md`
17. `docs/android/local-persistence.md`
18. `docs/android/room-schema.md`
19. `docs/android/room-relationships.md`
20. `docs/android/navigation.md`
21. `docs/android/ui/design-system.md`
22. `docs/android/ui/components.md`
23. `docs/android/ui/ui-performance.md`
24. `docs/android/player/architecture.md`
25. `docs/android/player/queue-rules.md`
26. `docs/android/player/states-and-events.md`
27. `docs/android/screens/home.md`
28. `docs/android/screens/search.md`
29. `docs/android/screens/library.md`
30. `docs/android/screens/playlists.md`
31. `docs/android/screens/player.md`
32. `docs/android/screens/player-layout.md`
33. `docs/android/screens/artist.md`
34. `docs/android/screens/album.md`
35. `docs/android/screens/downloads.md`
36. `docs/android/screens/settings.md`
37. `docs/server/architecture.md`
38. `docs/server/api-contract.md`
39. `docs/server/api-sync-flows.md`
40. `docs/server/jobs.md`
41. `docs/server/database-postgres.md`
42. `docs/server/postgres-relationships.md`
43. `docs/server/vector-search-qdrant.md`
44. `docs/server/storage.md`
45. `docs/server/providers/deezer.md`
46. `docs/server/providers/streaming-bridge.md`
47. `docs/server/security-and-secrets.md`
48. `docs/mcp/overview.md`
49. `docs/mcp/resources.md`
50. `docs/mcp/use-cases.md`
51. `docs/ops/logging-observability.md`
52. `docs/ops/env-vars.md`

## Rythme de mise a jour
- Toute creation ou suppression d'un document impose une mise a jour de `llms.txt`.
- Toute modification de structure impose une regeneration de `llms-full.txt`.
- Les documents produit et domaine doivent etre valides avant de documenter une implementation detaillee.
- Toute modification documentaire doit respecter `docs/documentation/style-guide.md`.
