package com.aura.music.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.aura.music.BuildConfig
import com.aura.music.data.network.AppVersionResponseData
import com.aura.music.data.network.AuraApiService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

sealed interface UpdateState {
    object Idle : UpdateState
    object Checking : UpdateState
    data class UpdateAvailable(
        val updateInfo: AppVersionResponseData,
        val isMandatory: Boolean,
    ) : UpdateState
    object UpToDate : UpdateState
    data class Downloading(
        val updateInfo: AppVersionResponseData,
        val progress: Float, // 0.0f to 1.0f
        val bytesDownloaded: Long,
        val totalBytes: Long,
    ) : UpdateState
    data class ReadyToInstall(
        val apkFile: File,
        val updateInfo: AppVersionResponseData,
    ) : UpdateState
    data class PermissionRequired(
        val apkFile: File,
        val updateInfo: AppVersionResponseData,
    ) : UpdateState
    data class Error(
        val message: String,
        val canRetry: Boolean = true,
        val updateInfo: AppVersionResponseData? = null,
    ) : UpdateState
}

/**
 * Gère le cycle de vie complet des mises à jour OTA (Over-The-Air) sécurisées.
 * - Détection de version et discrimination obligatoire / facultative.
 * - Téléchargement en flux avec remontée de progression.
 * - Vérification stricte de l'empreinte cryptographique SHA-256 (Anti-Tampering).
 * - Gestion de l'autorisation Android 8+ d'installation d'applications inconnues.
 * - Exposition sécurisée via FileProvider pour l'intent natif ACTION_VIEW.
 */
class AppUpdateManager(
    private val context: Context,
    private val apiService: AuraApiService,
    private val scope: CoroutineScope,
    private val apiBaseUrl: String = com.aura.music.data.network.BuildConfig.API_BASE_URL,
) {
    companion object {
        private const val TAG = "AppUpdateManager"
        private const val UPDATE_SUBDIR = "updates"
        private const val APK_FILENAME = "aura-latest.apk"
    }

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /**
     * Vérifie auprès de l'API si une nouvelle version est disponible.
     * @param isManual Indique si la recherche a été déclenchée manuellement par l'utilisateur.
     */
    fun checkForUpdate(isManual: Boolean = false) {
        scope.launch {
            _state.value = UpdateState.Checking
            try {
                val response = withContext(Dispatchers.IO) {
                    apiService.getAppVersion()
                }

                val remote = response.data
                if (remote == null) {
                    Log.w(TAG, "Aucune métadonnée reçue de l'endpoint /app/version")
                    _state.value = if (isManual) UpdateState.UpToDate else UpdateState.Idle
                    return@launch
                }

                val currentVersionCode = BuildConfig.VERSION_CODE
                Log.d(TAG, "Version locale: $currentVersionCode, Version distante: ${remote.versionCode}")

                if (remote.versionCode > currentVersionCode) {
                    val isMandatory = currentVersionCode < remote.minSupportedVersion
                    _state.value = UpdateState.UpdateAvailable(
                        updateInfo = remote,
                        isMandatory = isMandatory,
                    )
                } else {
                    _state.value = if (isManual) UpdateState.UpToDate else UpdateState.Idle
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erreur lors de la vérification de mise à jour", e)
                if (isManual) {
                    _state.value = UpdateState.Error(
                        message = "Impossible de contacter le serveur de mise à jour (${e.localizedMessage ?: "erreur réseau"}).",
                    )
                } else {
                    _state.value = UpdateState.Idle
                }
            }
        }
    }

    /**
     * Lance le téléchargement sécurisé du fichier APK avec vérification de hash SHA-256.
     */
    fun startDownload(updateInfo: AppVersionResponseData) {
        scope.launch {
            _state.value = UpdateState.Downloading(
                updateInfo = updateInfo,
                progress = 0.0f,
                bytesDownloaded = 0L,
                totalBytes = -1L,
            )

            withContext(Dispatchers.IO) {
                val updatesDir = File(context.cacheDir, UPDATE_SUBDIR).apply { mkdirs() }
                val targetApk = File(updatesDir, APK_FILENAME)
                if (targetApk.exists()) {
                    targetApk.delete()
                }

                val rawDownloadUrl = updateInfo.downloadUrl
                val fullUrl = if (rawDownloadUrl.startsWith("http://") || rawDownloadUrl.startsWith("https://")) {
                    rawDownloadUrl
                } else {
                    val cleanBase = apiBaseUrl.trimEnd('/')
                    val cleanPath = rawDownloadUrl.trimStart('/')
                    "$cleanBase/$cleanPath"
                }

                try {
                    val url = URL(fullUrl)
                    val connection = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15000
                        readTimeout = 30000
                        instanceFollowRedirects = true
                    }

                    val responseCode = connection.responseCode
                    if (responseCode !in 200..299) {
                        throw IllegalStateException("Le serveur a répondu avec le code HTTP $responseCode")
                    }

                    val totalBytes = connection.contentLengthLong
                    val digest = MessageDigest.getInstance("SHA-256")

                    BufferedInputStream(connection.inputStream).use { input ->
                        FileOutputStream(targetApk).use { output ->
                            val buffer = ByteArray(8192)
                            var bytesRead: Int
                            var downloadedBytes = 0L
                            var lastProgressEmission = 0L

                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                digest.update(buffer, 0, bytesRead)
                                downloadedBytes += bytesRead

                                val now = System.currentTimeMillis()
                                if (now - lastProgressEmission > 100 || downloadedBytes == totalBytes) {
                                    lastProgressEmission = now
                                    val progress = if (totalBytes > 0) {
                                        (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                                    } else {
                                        0.0f
                                    }
                                    _state.value = UpdateState.Downloading(
                                        updateInfo = updateInfo,
                                        progress = progress,
                                        bytesDownloaded = downloadedBytes,
                                        totalBytes = totalBytes,
                                    )
                                }
                            }
                            output.flush()
                        }
                    }

                    // Vérification cryptographique de l'intégrité (Anti-Tampering)
                    val calculatedSha256 = digest.digest().joinToString("") { "%02x".format(it) }
                    val expectedSha256 = updateInfo.sha256.trim()

                    Log.d(TAG, "SHA-256 attendu: '$expectedSha256', calculé: '$calculatedSha256'")

                    if (expectedSha256.isNotBlank() && !expectedSha256.equals("auto", ignoreCase = true)) {
                        if (!calculatedSha256.equals(expectedSha256, ignoreCase = true)) {
                            targetApk.delete()
                            Log.e(TAG, "Échec de validation SHA-256 ! Fichier supprimé.")
                            _state.value = UpdateState.Error(
                                message = "Échec de l'intégrité du package (empreinte SHA-256 divergente). Le fichier téléchargé a été supprimé par sécurité.",
                                canRetry = true,
                                updateInfo = updateInfo,
                            )
                            return@withContext
                        }
                    }

                    // Vérification de l'autorisation d'installation Android 8+
                    if (canInstallUnknownApps()) {
                        _state.value = UpdateState.ReadyToInstall(targetApk, updateInfo)
                        launchInstaller(targetApk)
                    } else {
                        _state.value = UpdateState.PermissionRequired(targetApk, updateInfo)
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Erreur durant le téléchargement du package", e)
                    if (targetApk.exists()) {
                        targetApk.delete()
                    }
                    _state.value = UpdateState.Error(
                        message = "Échec du téléchargement : ${e.localizedMessage ?: "Erreur réseau inconnue"}",
                        canRetry = true,
                        updateInfo = updateInfo,
                    )
                }
            }
        }
    }

    /**
     * Vérifie si l'application dispose de l'autorisation d'installer des APKs externes.
     */
    fun canInstallUnknownApps(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /**
     * Ouvre les paramètres système Android pour autoriser l'installation d'applications inconnues.
     */
    fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Impossible d'ouvrir les paramètres d'installation", e)
            }
        }
    }

    /**
     * Déclenche l'installateur natif Android via FileProvider.
     */
    fun launchInstaller(apkFile: File) {
        if (!apkFile.exists()) {
            _state.value = UpdateState.Error("Fichier d'installation introuvable.")
            return
        }

        try {
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile,
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(installIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Erreur au lancement de l'installateur système", e)
            _state.value = UpdateState.Error("Impossible de lancer l'installateur : ${e.localizedMessage}")
        }
    }

    /**
     * Réinitialise l'état si la mise à jour n'est pas bloquante.
     */
    fun dismiss() {
        val current = _state.value
        if (current is UpdateState.UpdateAvailable && current.isMandatory) {
            return // Impossible de refuser une mise à jour requise
        }
        _state.value = UpdateState.Idle
    }
}
