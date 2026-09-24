package com.aura.music.desktop.domain

import com.aura.music.data.network.AppVersionResponseData
import com.aura.music.data.network.AuraApiService
import com.aura.music.data.network.BuildConfig
import com.aura.music.desktop.utils.DesktopBuildConfig
import com.aura.music.desktop.utils.DesktopEnvironment
import com.aura.music.desktop.utils.DesktopInstallMode
import com.aura.music.desktop.utils.WindowsProcessHelper
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
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

sealed interface DesktopUpdateState {
    object Idle : DesktopUpdateState
    object Checking : DesktopUpdateState
    data class UpdateAvailable(
        val updateInfo: AppVersionResponseData,
        val isMandatory: Boolean,
        val installMode: DesktopInstallMode
    ) : DesktopUpdateState
    object UpToDate : DesktopUpdateState
    data class Downloading(
        val updateInfo: AppVersionResponseData,
        val progress: Float, // 0.0f à 1.0f
        val bytesDownloaded: Long,
        val totalBytes: Long
    ) : DesktopUpdateState
    data class ReadyToInstall(
        val installerFile: File,
        val updateInfo: AppVersionResponseData
    ) : DesktopUpdateState
    data class Error(
        val message: String,
        val canRetry: Boolean = true,
        val updateInfo: AppVersionResponseData? = null
    ) : DesktopUpdateState
    data class PostUpdateNotification(
        val success: Boolean,
        val message: String,
        val logFile: File? = null
    ) : DesktopUpdateState
}

class DesktopUpdateManager(
    private val apiService: AuraApiService,
    private val scope: CoroutineScope,
    private val apiBaseUrl: String = BuildConfig.API_BASE_URL
) {
    companion object {
        private const val PENDING_MARKER_FILENAME = ".update_pending.json"
        private const val ERROR_MARKER_FILENAME = ".update_pending.json.err"
        private const val SCRIPT_FILENAME = "aura_updater.bat"
        private const val LOG_FILENAME = "last_install.log"
    }

    private val _state = MutableStateFlow<DesktopUpdateState>(DesktopUpdateState.Idle)
    val state: StateFlow<DesktopUpdateState> = _state.asStateFlow()

    /**
     * Inspecte l'état post-mise à jour au démarrage de l'application.
     * Détecte si une mise à jour précédente a réussi ou échoué, nettoie les artefacts et notifie.
     */
    fun checkPostUpdateStatus() {
        val updatesDir = DesktopEnvironment.getUpdatesDir()
        val markerFile = File(updatesDir, PENDING_MARKER_FILENAME)
        val errorMarker = File(updatesDir, ERROR_MARKER_FILENAME)
        val logFile = File(updatesDir, LOG_FILENAME)

        if (!markerFile.exists() && !errorMarker.exists()) {
            return
        }

        val hasError = errorMarker.exists()
        var targetVersionName = ""
        var targetVersionCode = 0

        if (markerFile.exists()) {
            try {
                val content = markerFile.readText()
                val targetCodeMatch = Regex(""""target_version_code"\s*:\s*(\d+)""").find(content)
                val targetNameMatch = Regex(""""target_version_name"\s*:\s*"([^"]+)"""").find(content)
                targetVersionCode = targetCodeMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                targetVersionName = targetNameMatch?.groupValues?.get(1) ?: ""
            } catch (_: Exception) { }
        }

        // Nettoyage impératif des marqueurs
        if (markerFile.exists()) markerFile.delete()
        if (errorMarker.exists()) errorMarker.delete()

        val isActuallyUpdated = DesktopBuildConfig.VERSION_CODE >= targetVersionCode && targetVersionCode > 0

        if (hasError || (!isActuallyUpdated && targetVersionCode > 0)) {
            // Échec post-mortem constaté
            _state.value = DesktopUpdateState.PostUpdateNotification(
                success = false,
                message = "La mise à jour vers la version $targetVersionName n'a pas pu être installée. Votre version actuelle (${DesktopBuildConfig.VERSION_NAME}) est conservée.",
                logFile = if (logFile.exists()) logFile else null
            )
        } else if (isActuallyUpdated) {
            // Succès : purge automatique des anciens packages et scripts
            purgeOldUpdateArtifacts(updatesDir)
            _state.value = DesktopUpdateState.PostUpdateNotification(
                success = true,
                message = "AURA a été mis à jour avec succès vers la version ${DesktopBuildConfig.VERSION_NAME} !",
                logFile = null
            )
        }
    }

    /**
     * Purge les anciens installateurs .msi, fichiers partiels .part et scripts .bat.
     */
    private fun purgeOldUpdateArtifacts(updatesDir: File) {
        try {
            updatesDir.listFiles()?.forEach { file ->
                if (file.name.endsWith(".msi") || file.name.endsWith(".part") || file.name == SCRIPT_FILENAME) {
                    file.delete()
                }
            }
        } catch (_: Exception) { }
    }

    /**
     * Interroge l'API pour vérifier si une nouvelle version Desktop est disponible.
     */
    fun checkForUpdate(isManual: Boolean = false) {
        scope.launch {
            _state.value = DesktopUpdateState.Checking
            try {
                val response = withContext(Dispatchers.IO) {
                    apiService.getAppVersion(platform = "desktop")
                }

                val remote = response.data
                if (remote == null) {
                    _state.value = if (isManual) DesktopUpdateState.UpToDate else DesktopUpdateState.Idle
                    return@launch
                }

                val currentVersionCode = DesktopBuildConfig.VERSION_CODE
                if (remote.versionCode > currentVersionCode) {
                    val isMandatory = currentVersionCode < remote.minSupportedVersion
                    _state.value = DesktopUpdateState.UpdateAvailable(
                        updateInfo = remote,
                        isMandatory = isMandatory,
                        installMode = DesktopEnvironment.installMode
                    )
                } else {
                    _state.value = if (isManual) DesktopUpdateState.UpToDate else DesktopUpdateState.Idle
                }
            } catch (e: Exception) {
                if (isManual) {
                    _state.value = DesktopUpdateState.Error(
                        message = "Impossible de contacter le serveur de mise à jour (${e.localizedMessage ?: "erreur réseau"})."
                    )
                } else {
                    _state.value = DesktopUpdateState.Idle
                }
            }
        }
    }

    /**
     * Lance le téléchargement atomique avec calcul SHA-256 en flux direct.
     */
    fun startDownload(updateInfo: AppVersionResponseData) {
        scope.launch {
            _state.value = DesktopUpdateState.Downloading(
                updateInfo = updateInfo,
                progress = 0.0f,
                bytesDownloaded = 0L,
                totalBytes = -1L
            )

            withContext(Dispatchers.IO) {
                val updatesDir = DesktopEnvironment.getUpdatesDir()
                val filename = File(updateInfo.downloadUrl).name.ifBlank { "AURA-${updateInfo.versionName}.msi" }
                val targetFile = File(updatesDir, filename)
                val partFile = File(updatesDir, "$filename.part")

                if (partFile.exists()) {
                    partFile.delete()
                }

                val rawUrl = updateInfo.downloadUrl
                val fullUrl = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
                    rawUrl
                } else {
                    val cleanBase = apiBaseUrl.trimEnd('/')
                    val cleanPath = rawUrl.trimStart('/')
                    "$cleanBase/$cleanPath"
                }

                try {
                    val url = URL(fullUrl)
                    val connection = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15000
                        readTimeout = 60000
                        instanceFollowRedirects = true
                    }

                    val responseCode = connection.responseCode
                    if (responseCode !in 200..299) {
                        throw IllegalStateException("Le serveur a répondu avec le code HTTP $responseCode")
                    }

                    val totalBytes = connection.contentLengthLong
                    val digest = MessageDigest.getInstance("SHA-256")

                    BufferedInputStream(connection.inputStream).use { input ->
                        FileOutputStream(partFile).use { output ->
                            val buffer = ByteArray(65536) // Tampons de 64 Ko
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
                                    } else 0.0f

                                    _state.value = DesktopUpdateState.Downloading(
                                        updateInfo = updateInfo,
                                        progress = progress,
                                        bytesDownloaded = downloadedBytes,
                                        totalBytes = totalBytes
                                    )
                                }
                            }
                            output.flush()
                        }
                    }

                    // 1. Validation cryptographique Anti-Tampering
                    val calculatedSha = digest.digest().joinToString("") { "%02x".format(it) }
                    val expectedSha = updateInfo.sha256.trim()

                    if (expectedSha.isNotBlank() && !expectedSha.equals("auto", ignoreCase = true)) {
                        if (!calculatedSha.equals(expectedSha, ignoreCase = true)) {
                            partFile.delete()
                            _state.value = DesktopUpdateState.Error(
                                message = "Échec d'intégrité (hash SHA-256 divergent). Le fichier a été supprimé par sécurité.",
                                canRetry = true,
                                updateInfo = updateInfo
                            )
                            return@withContext
                        }
                    }

                    // 2. Renommage atomique vers le nom final
                    Files.move(
                        partFile.toPath(),
                        targetFile.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                    )

                    // 3. Passage à l'état prêt à installer
                    _state.value = DesktopUpdateState.ReadyToInstall(targetFile, updateInfo)

                    // Si mode installé sous Windows, on déclenche l'installation
                    if (DesktopEnvironment.installMode == DesktopInstallMode.INSTALLED && DesktopEnvironment.isWindows) {
                        triggerInstallationAndExit(targetFile, updateInfo)
                    }

                } catch (e: Exception) {
                    if (partFile.exists()) partFile.delete()
                    _state.value = DesktopUpdateState.Error(
                        message = "Échec du téléchargement : ${e.localizedMessage ?: "Erreur réseau"}",
                        canRetry = true,
                        updateInfo = updateInfo
                    )
                }
            }
        }
    }

    /**
     * Déclenche l'installation sous Windows via le script watchdog et ferme AURA si le démarrage système réussit.
     */
    fun triggerInstallationAndExit(installerFile: File, updateInfo: AppVersionResponseData) {
        val updatesDir = DesktopEnvironment.getUpdatesDir()
        val logFile = File(updatesDir, LOG_FILENAME)
        val currentExe = File(DesktopEnvironment.appDir, "AURA.exe")
        val markerFile = File(updatesDir, PENDING_MARKER_FILENAME)
        val currentPid = ProcessHandle.current().pid()

        // 1. Écriture du marqueur d'attente
        try {
            markerFile.writeText(
                """
                {
                    "target_version_code": ${updateInfo.versionCode},
                    "target_version_name": "${updateInfo.versionName}",
                    "timestamp": ${System.currentTimeMillis()},
                    "log_path": "${logFile.absolutePath.replace("\\", "\\\\")}"
                }
                """.trimIndent()
            )
        } catch (_: Exception) { }

        // 2. Génération du script watchdog durci
        val scriptFile = File(updatesDir, SCRIPT_FILENAME)
        scriptFile.writeText(generateUpdaterScriptContent())

        // 3. Lancement Win32 masqué et détaché via JNA
        val launchResult = WindowsProcessHelper.launchHiddenBatch(
            batFile = scriptFile,
            args = listOf(
                currentPid.toString(),
                installerFile.absolutePath,
                logFile.absolutePath,
                currentExe.absolutePath,
                markerFile.absolutePath
            ),
            workingDir = updatesDir
        )

        // 4. Contrôle impératif avant toute sortie de la JVM
        launchResult.onSuccess {
            // Le watchdog est actif : arrêt immédiat d'AURA pour libérer les handles
            kotlin.system.exitProcess(0)
        }.onFailure { error ->
            // Le watchdog n'a pas pu démarrer : NETTOYAGE SANS FERMER L'APPLICATION
            if (markerFile.exists()) markerFile.delete()
            _state.value = DesktopUpdateState.Error(
                message = "Impossible d'initier le processus de mise à jour système (${error.message}). AURA reste ouvert.",
                canRetry = true,
                updateInfo = updateInfo
            )
        }
    }

    fun dismiss() {
        val current = _state.value
        if (current is DesktopUpdateState.UpdateAvailable && current.isMandatory) {
            return
        }
        _state.value = DesktopUpdateState.Idle
    }

    private fun generateUpdaterScriptContent(): String {
        return """
@echo off
setlocal enabledelayedexpansion

set PARENT_PID=%~1
set INSTALLER_PATH=%~2
set LOG_PATH=%~3
set APP_EXE=%~4
set PENDING_MARKER=%~5

echo [%date% %time%] Demarrage du watchdog de mise a jour AURA > "%LOG_PATH%"

:: 1. Attente de la fermeture de la JVM parente avec timeout de 30 secondes
set WAIT_COUNT=0
:WAIT_PID
set /a WAIT_COUNT+=1
if !WAIT_COUNT! gtr 30 (
    echo [%date% %time%] ERREUR: Le processus parent (PID %PARENT_PID%) ne s'est pas termine dans le delai de 30s. >> "%LOG_PATH%"
    echo {"status": "error", "code": -1, "reason": "parent_pid_timeout", "log": "%LOG_PATH%"} > "%PENDING_MARKER%.err"
    goto RELAUNCH
)

tasklist /fi "PID eq %PARENT_PID%" 2>nul | find "%PARENT_PID%" >nul
if !ERRORLEVEL! == 0 (
    timeout /t 1 /nobreak >nul
    goto WAIT_PID
)

:: Libération des verrous Windows résiduels
timeout /t 1 /nobreak >nul

:: 2. Exécution passive du MSI avec logs complets
echo [%date% %time%] Lancement de Windows Installer... >> "%LOG_PATH%"
msiexec.exe /i "%INSTALLER_PATH%" /passive /norestart /log "%LOG_PATH%"
set MSI_RETURN=!ERRORLEVEL!

:: 3. Tolérance au verrou temporaire (1603) : retry unique apres 2s
if !MSI_RETURN! == 1603 (
    echo [%date% %time%] Code 1603 detecte. Nouvelle tentative dans 2 secondes... >> "%LOG_PATH%"
    timeout /t 2 /nobreak >nul
    msiexec.exe /i "%INSTALLER_PATH%" /passive /norestart /log "%LOG_PATH%"
    set MSI_RETURN=!ERRORLEVEL!
)

:: Cas 1618 (Service Windows Installer occupe) : Jusqu'a 3 retries espaces de 5s
set RETRY_1618=0
:LOOP_1618
if !MSI_RETURN! == 1618 (
    set /a RETRY_1618+=1
    if !RETRY_1618! leq 3 (
        echo [%date% %time%] Code 1618 (Installateur occupe). Tentative !RETRY_1618!/3 dans 5s... >> "%LOG_PATH%"
        timeout /t 5 /nobreak >nul
        msiexec.exe /i "%INSTALLER_PATH%" /passive /norestart /log "%LOG_PATH%"
        set MSI_RETURN=!ERRORLEVEL!
        goto LOOP_1618
    )
)

:: 4. Rapport de statut post-execution
if !MSI_RETURN! neq 0 (
    echo [%date% %time%] Echec d'installation avec le code !MSI_RETURN!. >> "%LOG_PATH%"
    echo {"status": "error", "code": !MSI_RETURN!, "log": "%LOG_PATH%"} > "%PENDING_MARKER%.err"
) else (
    echo [%date% %time%] Installation finalisee avec succes (code 0). >> "%LOG_PATH%"
)

:: 5. Relance systematique de l'application AURA
:RELAUNCH
if exist "%APP_EXE%" (
    echo [%date% %time%] Relance d'AURA: "%APP_EXE%" >> "%LOG_PATH%"
    start "" "%APP_EXE%"
)

endlocal
exit
        """.trimIndent()
    }
}
