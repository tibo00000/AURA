# Cloud Sync Screen (Android)

## Role
Permettre à l'utilisateur de gérer son stockage de fichiers cloud sur le VPS personnel, de voir la jauge d'espace disque disponible, d'activer ou de désactiver la synchronisation automatique des fichiers, et de gérer manuellement le téléchargement ou l'upload direct de pistes.

## Structure générale
- Scroll vertical unique.
- En-tête avec bouton retour et titre `Stockage Cloud`.
- Carte Jauge de stockage VPS (espace occupé sur un quota virtuel de 5 Go).
- Sélecteur de synchronisation automatique (switch simple).
- Ligne d'actions en lot (boutons d'upload ou de récupération globale).
- Chips de filtrage :
  - `À récupérer` (Fichiers cloud absents localement).
  - `À uploader` (Fichiers locaux non synchronisés).
  - `Tout le Cloud` (Inventaire complet du cloud).
- Bouton de tri (Taille / Nom).
- Liste de pistes scrollable avec états d'opérations asynchrones en cours et actions contextuelles adaptées.

## Jauge de stockage VPS
- Représentation visuelle de la consommation disque sous forme de barre de progression orange (`BlazeOrange`).
- Affiche la taille totale occupée en Mo/Go et la fraction par rapport au quota de 5 Go.

## Actions contextuelles par piste
- **Télécharger (Cloud -> Local)** : Télécharge le fichier MP3 physique, met à jour `canonicalAudioSourceType` en `downloaded`.
- **Envoyer (Local -> Cloud)** : Téléverse le fichier local vers le serveur VPS via multipart.
- **Supprimer (du Cloud)** : Supprime le fichier physique du VPS via API.

## États
- **Chargement** : Indicateur de chargement circulaire pendant la récupération de l'inventaire.
- **Vide** : Message descriptif si aucune piste ne correspond au filtre actif.
- **Erreur** : Notification snackbar en cas de problème de connexion ou d'échec d'API.
