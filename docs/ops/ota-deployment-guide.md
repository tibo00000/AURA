# Guide de Déploiement et de Mises à Jour OTA (Over-The-Air)

Ce guide décrit la procédure standardisée et sécurisée pour publier une mise à jour de l'application Android **AURA** et la déployer via le serveur OTA.

---

## 1. Principes d'Architecture & Sécurité

Le système de mise à jour AURA repose sur 4 piliers normatifs :

1. **Transport Chiffré TLS (HTTPS)** :
   - Le point d'entrée officiel est `https://aura-prod.duckdns.org` via le reverse-proxy Caddy.
   - Tous les transferts de binaire et requêtes de version sont chiffrés.
2. **Contrôle d'Intégrité Strict SHA-256 (Anti-Tampering)** :
   - Le serveur calcule dynamiquement l'empreinte SHA-256 du fichier APK si le champ `sha256` est fixé à `"auto"` dans `version.json`.
   - L'application mobile télécharge le binaire en flux et vérifie l'empreinte SHA-256 avant toute tentative d'installation. Si le hash diverge, le binaire est immédiatement détruit.
3. **Exposition Sécurisée Android (FileProvider)** :
   - L'APK est stocké dans le cache interne de l'application (`context.cacheDir/updates/aura-latest.apk`).
   - L'installateur natif Android reçoit un URI de contenu temporaire via `FileProvider` (`com.aura.music.fileprovider`) avec `FLAG_GRANT_READ_URI_PERMISSION`. Aucune URI `file://` n'est exposée.
4. **Discrimination Obligatoire / Facultative** :
   - Si `client.versionCode < remote.min_supported_version` : Mise à jour **obligatoire** (bloquante, dialogue non dismissible, aucun bouton "Plus tard").
   - Si `client.versionCode >= remote.min_supported_version` et `< remote.version_code` : Mise à jour **facultative** (bouton "Plus tard" disponible).
   - Si `client.versionCode >= remote.version_code` : L'application est **à jour** (aucun pop-up).

---

## 2. Procédure Pas-à-Pas pour Publier une Mise à Jour

### Étape 1 : Incrémenter la version dans Android Studio

Dans [`androidApp/build.gradle.kts`](file:///c:/Users/thiba/Desktop/AURA-workspace/androidApp/build.gradle.kts) :
- Incrémenter obligatoirement `versionCode` (+1) :
  ```kotlin
  versionCode = 2 // Puis 3, 4, etc.
  versionName = "0.2.0" // Sémantique SemVer (MAJOR.MINOR.PATCH)
  ```

### Étape 2 : Vérifier le ciblage d'API dans `local.properties`

Pour que l'APK pointe bien sur le serveur de production sécurisé en HTTPS :
```properties
API_BASE_URL=https://aura-prod.duckdns.org
```

### Étape 3 : Compiler l'APK

Dans Android Studio :
1. Menu **Build** $\rightarrow$ **Build Bundle(s) / APK(s)** $\rightarrow$ **Build APK(s)**.
2. Une notification Android Studio apparaît : cliquer sur **locate** ou récupérer l'APK généré sous :
   `androidApp\build\outputs\apk\debug\app-debug.apk`  
   *(Ou votre build release signée).*

---

### Étape 4 : Déposer l'APK sur le VPS

Depuis votre terminal local (PowerShell) :
```powershell
scp "c:\Users\thiba\Desktop\AURA-workspace\androidApp\build\outputs\apk\debug\app-debug.apk" root@212.90.121.80:/tmp/latest.apk
```

Puis sur le terminal du VPS :
```bash
# Copier l'APK depuis /tmp vers le volume du conteneur
docker cp /tmp/latest.apk aura-api:/app/downloads/updates/latest.apk
```

---

### Étape 5 : Mettre à jour `version.json` sur le VPS

Toujours sur le VPS, exécutez la commande suivante pour aligner le descripteur de version :

```bash
docker exec -i aura-api bash -c 'cat << "EOF" > /app/downloads/updates/version.json
{
  "version_code": 2,
  "version_name": "0.2.0",
  "download_url": "/app/updates/latest.apk",
  "sha256": "auto",
  "release_notes": "Tri dynamique des résultats, streaming éphémère multi-comptes et mise à jour OTA sécurisée.",
  "min_supported_version": 1
}
EOF'
```

> **Astuce `sha256: "auto"`** : Le serveur FastAPI calcule lui-même l'empreinte SHA-256 exacte dès que la requête `/app/version` arrive. Vous n'avez pas besoin de calculer le hash manuellement !

---

### Étape 6 : Vérifier le déploiement

Sur le VPS, vérifiez que l'API et Caddy renvoient les bonnes informations :
```bash
curl https://aura-prod.duckdns.org/app/version
```

Vous devez recevoir :
```json
{
  "data": {
    "version_code": 2,
    "version_name": "0.2.0",
    "download_url": "/app/updates/latest.apk",
    "sha256": "3a7b...",
    "release_notes": "...",
    "min_supported_version": 1
  },
  "error": null,
  "meta": {}
}
```

---

## 3. Distribution & Cycle Utilisateurs

1. **Pour les nouveaux utilisateurs (ex: votre ami)** :
   - Envoyez-lui directement l'APK généré à l'Étape 3 (`AURA-v0.2.0.apk`).
   - Comme cet APK a `versionCode = 2`, dès son installation, l'application constatera qu'elle est déjà sur la version `2` du serveur $\rightarrow$ **aucun dialogue de mise à jour ne lui sera affiché**.
2. **Pour les utilisateurs déjà installés (sur la version 1)** :
   - Dès qu'ils ouvrent l'application (ou cliquent sur "Vérifier les mises à jour" dans les Paramètres), l'application détecte que `versionCode (1) < remote (2)`.
   - Le pop-up s'ouvre, télécharge l'APK, vérifie le SHA-256, et lance l'installateur natif Android automatiquement.
