# Android Auto Integration in AURA

This document describes the design, layout, file mapping, and security configurations implemented to support Android Auto inside the **AURA** application.

---

## 1. File Structure & Placement

The following files govern the Android Auto integration:

* **Service Entry Point**: `androidApp/src/main/java/com/aura/music/service/AuraCarAppService.kt`
  * Initiates the car connection, checks host security, and spawns the lifecycle session.
* **Session Coordinator**: `androidApp/src/main/java/com/aura/music/service/AuraCarSession.kt`
  * Manages navigation flow transitions and creates the initial screen stack.
* **Screen UI Layout**: `androidApp/src/main/java/com/aura/music/ui/car/LibraryBrowseScreen.kt`
  * Renders the custom templated list showing offline downloaded tracks and binds user interactions to playback.
* **Playback Integration Service**: `androidApp/src/main/java/com/aura/music/service/PlaybackService.kt`
  * Exposes the Media3 `MediaLibraryService` catalog to allow background playback controls, steering wheel interactions, and Bluetooth metadata sharing.
* **Manifest & Metadata Resources**:
  * `androidApp/src/main/AndroidManifest.xml` — Declares service capabilities and grants URI permissions.
  * `androidApp/src/main/res/xml/automotive_app_desc.xml` — Exposes standard `media` capability tags to the car dashboard.
  * `androidApp/src/main/res/values/allowed_hosts.xml` — Lists trusted car projection package signatures (Gearhead, Emulator).
  * `androidApp/src/main/res/xml/file_paths.xml` — Configures the shared directory paths for the `FileProvider`.

---

## 2. Layout Structure & Design

The application's interface inside Android Auto consists of two layers: the **Custom Templated UI** and the **Native System Player UI**.

### Custom Templated UI (AURA App Screen)
When the user opens AURA from the car app drawer, it renders the custom templated interface created using the Jetpack Car App Library:
1. **Header Action**: Back button.
2. **Template Type**: `ListTemplate`.
3. **Title**: "Titres Hors-ligne".
4. **List Rows**: A scrolling list of tracks. Each row is a `Row` displaying:
   * **Title**: Track title.
   * **Subtitle**: Artist name (or "Artiste inconnu").
   * **Thumbnail Image**: Loaded from the local `coverUri` as a downsampled `Bitmap` inside a `CarIcon`, falling back to a default Play icon if absent.
   * **Interaction**: Clicking the row dispatches a `PlayerEvent.PlayTrack` event to AURA's `PlaybackOrchestrator`, initiating playback.

### Native System Player UI (Automotive Media Controls)
Once a track begins playing, the OS opens its default automotive media player template (showing track title, album art, progress bar, play/pause/skip buttons).
* **Metadata Bridge**: The title, artist name, and album title are bound from `TrackListRow` through the `.toMediaItem(Context)` extension function to Media3.
* **Steering & Keys**: All hardware playback control events (AVRCP skip keys, play/pause toggles) are mapped natively by the underlying `MediaSession`.

---

## 3. Cover Art Display & FileProvider Sharing

### The Problem
Android Auto runs inside a separate system process (`com.google.android.projection.gearhead`) which lacks read permissions for AURA's private sandbox folder (`/data/user/0/com.aura.music/files/...`). Passing raw local file URIs to the car's native MediaSession causes a `SecurityException` and results in missing album art on the dashboard. Furthermore, some versions of the Android Auto host do not automatically inherit temporary URI read permissions from a custom `FileProvider` passed in media metadata.

### The Solution (Double Security Layer)
We implemented a robust dual-path resolution in `TrackListRow.toMediaItem(Context)`:

1. **FileProvider Fallback (Content URI)**:
   Local file paths (`file://` or `/`) are dynamically translated into `content://com.aura.music.fileprovider/...` URIs using `androidx.core.content.FileProvider.getUriForFile`.
2. **Direct Byte Serialization (setArtworkData) - Primary Path**:
   To bypass all process/file permission constraints, we load the local cover file as a byte array (`ByteArray`) and pass it directly to Media3:
   ```kotlin
   metadataBuilder.setArtworkData(artworkData, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
   ```
   This serializes the bitmap inside the `MediaItem` binder payload, transferring it directly to the car screen.
3. **TransactionTooLargeException Protection**:
   Since the Binder transaction buffer is shared and limited (1 MB), large artwork files could crash the application. We implemented a helper `getArtworkBytes()` that:
   * Instantly reads small covers (< 100 KB).
   * Downsamples and compresses larger images into a lightweight JPEG (< 30 KB, max 300x300 pixels) before conversion, guaranteeing memory safety.

