# Downloads Screen Layout

## Objectif
Definir le layout et l'architecture visuelle de l'ecran de suivi des telechargements asynchrones, conforme a la charte visuelle d'AURA (Outfit, Outfit Medium, Outlined Cards et harmonies sombres).

## Architecture verticale
- **Header** : 
  - Bouton retour standard AURA
  - Titre "Téléchargements"
  - Compteur global de tâches actives en petit sous-texte
- **Barre de filtres (Tabs)** :
  - Ligne sticky horizontale avec 4 options cliquables :
    - `En attente` (jobs avec statut `queued`)
    - `En cours` (jobs avec statut `running`)
    - `Terminés` (jobs avec statut `succeeded`)
    - `Erreurs` (jobs avec statut `failed` ou `cancelled`)
  - Indicateur visuel d'onglet actif (degrade ou couleur accentuee)
- **Liste principale** :
  - Liste verticale reactive (`LazyColumn`) affichant les `DownloadJobRow` correspondants a l'onglet actif.

---

## Le composant `DownloadJobRow`

Chaque ligne represente une tache de telechargement et s'adapte selon son statut :

### 1. Partie gauche (Visuel)
- Cover de l'album associee si elle a pu etre résolue (par Deezer), ou placeholder graphique AURA si manquante.
- Petite icône d'état en superposition pour identifier le statut au premier coup d'œil.

### 2. Partie centrale (Informations)
- **Titre de la piste** (texte en Outfit Medium, tronque si trop long).
- **Artiste** (texte secondaire plus petit).
- **Indicateur de progression** :
  - Pour `running` : Barre de progression horizontale (`LinearProgressIndicator`) affichant la valeur de `progress_percent` en temps reel, avec le pourcentage ecrit a cote.
  - Pour `queued` : Texte secondaire indiquant "Dans la file d'attente...".
  - Pour `failed` : Libellé d'erreur rouge avec description courte du problème (ex: "Challenge failed").
  - Pour `succeeded` : Poids du fichier final en Mo (ex: "12.9 Mo") et duree.

### 3. Partie droite (Actions contextuelles)
- **Queued / Running** : Bouton d'annulation (si l'API ou le client le permettent).
- **Failed** : Bouton "Réessayer" (icône de recharge/retry) qui declenche l'appel `POST /downloads/{id}/retry`.
- **Succeeded** : Bouton de lecture directe ou menu contextuel (Favoris, Playlist, Supprimer le fichier).

---

## États de l'écran

Pour chaque filtre (onglet) selectionne :

* **État vide (`empty`)** :
  - Utilise le composant reutilisable `DownloadStateCard` (defini dans `ScreenSharedComponents.kt`).
  - Affiche une icône d'illustration dediee, un titre et un message d'explication factuel (ex: *"Vos titres téléchargés apparaîtront ici pour une lecture hors-ligne."*).
* **État chargement (`loading`)** :
  - Spinner de chargement discret au centre de l'ecran.
* **État pret (`loaded`)** :
  - Affichage de la liste complete des taches.
  - La liste se met a jour automatiquement de maniere reactive en temps reel (polling sur l'API ou observation de la base Room locale).
* **État d'erreur réseau (`network_error`)** :
  - Bandeau d'erreur rouge non bloquant en haut de l'ecran si l'API est injoignable, avec bouton de rafraîchissement manuel.
