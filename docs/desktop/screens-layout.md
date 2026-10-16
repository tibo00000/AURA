# Desktop Layout Specification - AURA

This document defines the layout architecture and visual design system of the AURA Desktop application.

## Layout Structure
The interface uses a **3-pane vertical structure** optimized for widescreen layouts (minimum recommended resolution: `1280x800`):

```
+---------------------------------------------------------------------------------------------------+
|                                                                                                   |
|  +------------+-------------------------------------------------------------------+------------+  |
|  |            |                                                                   |            |  |
|  |  Sidebar   |  Center Content Area                                              |  Queue     |  |
|  |  (Left)    |  - Responsive grids (albums, artists)                             |  Panel     |  |
|  |            |  - Rails and track list tables                                    |  (Right)   |  |
|  |            |  - Classical stack navigation (Home, Search, Library, Settings)   |            |  |
|  |  Width:    |                                                                   |  Width:    |  |
|  |  260.dp    |  Width: Fill Weight (1f)                                          |  300.dp    |  |
|  |            |                                                                   |            |  |
|  +------------+-------------------------------------------------------------------+------------+  |
|  |                                                                                             |  |
|  |  Bottom Playback Bar (Height: 100.dp)                                                       |  |
|  |  - Controls, Seekbar, Like, Volume Slider                                                   |  |
|  |                                                                                             |  |
|  +---------------------------------------------------------------------------------------------+  |
|                                                                                                   |
+---------------------------------------------------------------------------------------------------+
```

---

## 1. Left Sidebar Panel (Navigation & Library)
- **Width**: `260.dp`
- **Background**: `OffBlack` (`#0F0F0F`)
- **Key Elements**:
  - **Brand Section**: Title "AURA" with a `BlazeOrange` (`#FFFF5700` / `#FF6B00`) status indicator circle.
  - **Main Navigation Menu**:
    - **Accueil (Home)**: High-level overview, statistics, and quick-access items.
    - **Recherche (Search)**: Hybrid search input and results dashboard.
    - **Bibliothèque (Library)**: Local track tables, album/artist grids, and playlists.
    - **Favoris (Favorites)**: Quick access to the user's liked tracks.
    - **Paramètres (Settings)**: Supabase login panel, Loom-powered local scanner.
  - **Library Playlists Section**:
    - "MES PLAYLISTS" label with a "+" button to quick-create local playlists.
    - Scrollable list of user playlists with dynamic item counts.

---

## 2. Center Content Area (Stack Navigation)
- **Width**: `weight(1f)` (Takes up remaining horizontal space)
- **Background**: `DeepBlack` (`#050505`)
- **Padding**: Horizontal `32.dp`, Vertical `24.dp`
- **Supported Screens**:
  - **Accueil**: Displays welcoming message, library stats (tracks, liked tracks, playlists), and recently played items.
  - **Recherche**: Text field with real-time hybrid results. Grouped results:
    - *Albums Grid* (Responsive cards using `SharedRailCard`)
    - *Artists Rail*
    - *Online Tracks* vs *Local Tracks*
  - **Bibliothèque**: Nested tabs/categories (Titres, Albums, Artistes, Playlists). When clicking a card, details are pushed onto the view stack.
  - **Favoris**: Lists all liked tracks with quick play actions.
  - **Détail Artiste/Album/Playlist**: Hero banner with title, artwork, and full track table listing tracks with index, title, artist, album, duration, and custom actions.
  - **Paramètres**: Panel for account management (email/password form, status) and background directory indexer. Contient un bouton d'accès vers l'écran dédié de gestion du cloud.
  - **Gestion Fichiers Cloud (CloudSyncScreen)** : Écran avec disposition split double colonne optimisée pour écran large :
    - *Colonne Gauche* (~320dp) : Jauge d'occupation du stockage VPS AURA (barre de progression orange `BlazeOrange` sur 5 Go), bouton de rafraîchissement manuel, et commutateur pour la synchronisation automatique en arrière-plan.
    - *Colonne Droite* (flexible) : Onglets de filtrage (« À récupérer », « À uploader », « Tout le Cloud »), actions globales en lot (Tout récupérer / Tout sauvegarder), et liste de pistes avec actions contextuelles (Télécharger, Envoyer, Supprimer) et suivi d'opérations asynchrones en cours.

---

## 3. Right Queue Panel
- **Width**: `300.dp`
- **Background**: `OffBlack` (`#0F0F0F`)
- **Border**: Left border with `HairlineDark` (`#1F1F1F`)
- **Key Elements**:
  - Title "File d'attente" with current size and a "Vider" button.
  - **Current Track Card**: Displays artwork, title, and artist of the currently playing track.
  - **Upcoming Tracks List**: Drag-and-drop support (reorderable list) showing tracks from `QueueManager` (both Priority Queue and Context Queue). Each item features a remove button.

---

## 4. Bottom Playback Bar
- **Height**: `100.dp`
- **Background**: `OffBlack` (`#0F0F0F`)
- **Border**: Top border with `HairlineDark` (`#1F1F1F`)
- **Panels**:
  - **Left Section (Metadata)**: Small artwork thumbnail, title, artist, and Favorite/Like toggle button.
  - **Center Section (Playback Controls & Timeline)**:
    - Row of buttons: Shuffle, Previous, Play/Pause (large filled button), Next, Repeat.
    - Seekbar with a slider, current playback position, and remaining/total duration.
  - **Right Section (Volume & Sound)**: Volume icon and a horizontal volume slider mapped directly to the JavaFX Media engine volume controls.

---

## Theme & Brand Guidelines
The AURA desktop interface enforces the `AuraTheme` dark mode by default:
- **Base Colors**:
  - `DeepBlack`: `#050505` (Main window background)
  - `OffBlack`: `#0B0B0B` / `#0F0F0F` (Panels, Bottom Bar, Sidebar)
  - `DarkGraphite`: `#1E1E1E` (Active item backgrounds, cards)
  - `ElevatedGraphite`: `#2A2A2A` (Hovered item state)
  - `HairlineDark`: `#161616` / `#222222` (Borders and separators)
- **Brand Colors**:
  - `BlazeOrange`: `#FF6B00` / `#FFFF5700` (Highlights, primary buttons, active text)
  - `TextPrimary`: `#FFFFFF`
  - `TextSecondary`: `#A0A0A0`
  - `TextMuted`: `#626262`
