# Éditeur de Métadonnées & Tags ID3 Mobile

## 1. Objectif
Fournir aux utilisateurs un moyen fluide, moderne et robuste de modifier les métadonnées de leurs fichiers audio physiques directement depuis l'application mobile AURA, tout en assurant une continuité stricte entre la base locale Room, le fichier MP3 physique (tags ID3v2.3) et l'index système Android (`MediaScanner`).

---

## 2. Architecture & Continuité 3 Couches

Toute modification de métadonnées s'exécute selon un cycle à 3 couches synchronisées :

```
                                +-----------------------------------+
                                |  EditTrackMetadataBottomSheet     |
                                |  (Suggestions Deezer ou Manuel)   |
                                +-----------------+-----------------+
                                                  |
                                                  v
                                +-----------------+-----------------+
                                |    LocalLibraryRepository         |
                                |     updateTrackMetadata()         |
                                +--------+--------+--------+--------+
                                         |        |        |
                   +---------------------+        |        +---------------------+
                   |                              |                              |
                   v                              v                              v
    +--------------+-------------+  +-------------+------------+   +-------------+------------+
    |        Couche 1 :          |  |        Couche 2 :        |   |        Couche 3 :        |
    |        Base Room           |  |     Fichier Physique     |   |    Android MediaStore    |
    |  - TrackEntity             |  |  - AudioTagWriter        |   |  - MediaScannerConnection|
    |  - ArtistEntity            |  |    (Frames ID3v2.3)      |   |    pour rafraîchir       |
    |  - AlbumEntity             |  |  - ImageCompressionUtils |   |    l'index système OS    |
    +----------------------------+  +--------------------------+   +--------------------------+
```

### Couche 1 — Base de données Room
- Mise à jour atomique de l'entité `TrackEntity` (titre, artiste, album, pochette, date de modification).
- Réconciliation et rattachement canonique aux tables `ArtistEntity` et `AlbumEntity` via les identifiants normalisés (`artistIdOf`, `albumIdOf`).
- **Règle anti-écrasement** : Lors des scans périodiques ou au démarrage de l'appli (`refreshLocalMediaIndex`), les métadonnées personnalisées stockées dans Room ont la priorité absolue et ne sont jamais écrasées par les anciennes métadonnées brutes du `MediaStore`.

### Couche 2 — Fichier physique MP3 (Tags ID3v2.3)
- Écriture directe et atomique dans le fichier physique via `AudioTagWriter` :
  - Parsing de l'en-tête ID3v2 existant (détection de la taille syncsafe).
  - Écriture des frames ID3v2.3 standard avec encodage UTF-16LE avec BOM :
    - `TIT2` : Titre du morceau
    - `TPE1` : Nom de l'artiste principal
    - `TALB` : Nom de l'album
    - `TRCK` : Numéro de piste
    - `TYER` : Année de sortie
    - `APIC` : Pochette intégrée (format JPEG compressé, type `0x03` Cover Front).
  - Création préalable d'un fichier temporaire `.tmp` puis remplacement atomique sans altérer le flux binaire audio MP3.

### Couche 3 — Indexation Système (`MediaScannerConnection`)
- Déclenchement de `MediaScannerConnection.scanFile()` pour notifier Android de la mise à jour des tags physiques, garantissant la synchronisation de l'écosystème Android externe (lecteurs tiers, Android Auto, explorateurs de fichiers).

---

## 3. Optimisation & Compression d'Images (`ImageCompressionUtils`)

Afin d'éviter le gonflement de la base de données Room et des fichiers MP3 :
- **Dimensions maximales** : 500x500 pixels (redimensionnement bilinéaire proportionnel).
- **Format & Compression** : JPEG avec un taux de qualité de 80% (poids moyen optimisé entre 40 Ko et 80 Ko).
- **Stockage local persistant** : `context.filesDir/covers/cover_<trackId>.jpg`.

---

## 4. Expérience Utilisateur (UI / UX)

Le composant modal `EditTrackMetadataBottomSheet` propose deux parcours complémentaires :

### Cas 1 : Morceau existant au catalogue (Suggestions Deezer automatiques)
- Recherche asynchrone instantanée via l'API Deezer lors de l'ouverture du BottomSheet.
- Affichage de cartes de suggestions interactives comprenant la miniature officielle, le titre officiel, l'artiste et l'album.
- Bouton **"Appliquer"** (1-clic) qui pré-remplit instantanément l'ensemble du formulaire et télécharge la pochette officielle.

### Cas 2 : Morceau indépendant / hors-catalogue (Édition Manuelle)
- Aperçu grand format de la pochette (140x140 dp, coins arrondis 16 dp).
- Sélection de pochette personnalisée depuis la galerie via Android PhotoPicker (`PickVisualMediaRequest`).
- Champs de saisie stylisés Material 3 : Titre, Artiste, Album, Numéro de piste, Année.
- Bouton d'enregistrement avec retour visuel réactif.

---

## 5. Gestion des Permissions Scoped Storage (Android 11+)

- Sur Android 11+ (API 30+), l'écriture directe dans les fichiers MP3 partagés (`/storage/emulated/0/...`) nécessite la permission `MANAGE_EXTERNAL_STORAGE` ("Accès à tous les fichiers").
- `StoragePermissionHelper` vérifie l'état de l'autorisation via `Environment.isExternalStorageManager()`.
- Une bannière non intrusive permet à l'utilisateur d'activer cette autorisation en 1 clic dans les réglages système Android. Une fois accordée, l'accès est permanent et l'application ne redemande plus jamais l'autorisation.
