package com.aura.music.desktop.utils

import java.io.File

object DesktopBuildConfig {
    const val VERSION_CODE = 1
    const val VERSION_NAME = "1.0.0"
}

enum class DesktopInstallMode {
    INSTALLED,
    PORTABLE
}

object DesktopEnvironment {
    val isWindows: Boolean by lazy {
        System.getProperty("os.name")?.lowercase()?.contains("win") == true
    }

    /**
     * Répertoire racine de l'application (où réside l'exécutable ou le jar).
     */
    val appDir: File by lazy {
        val resDir = System.getProperty("compose.application.resources.dir")
        if (resDir != null) {
            File(resDir).parentFile ?: File(".")
        } else {
            File(System.getProperty("user.dir") ?: ".")
        }
    }

    /**
     * Détecte si l'application s'exécute en mode portable (dossier autonome avec .portable)
     * ou en mode installé (MSI dans %LOCALAPPDATA%\Programs\AURA).
     */
    val installMode: DesktopInstallMode by lazy {
        val portableSentinel = File(appDir, ".portable")
        val currentWorkingDirPortable = File(".portable")
        
        if (portableSentinel.exists() || currentWorkingDirPortable.exists()) {
            DesktopInstallMode.PORTABLE
        } else {
            val localAppData = System.getenv("LOCALAPPDATA")
            if (localAppData != null && appDir.absolutePath.startsWith(File(localAppData).absolutePath)) {
                DesktopInstallMode.INSTALLED
            } else {
                // Par défaut, si non marqué .portable et sur Windows
                DesktopInstallMode.INSTALLED
            }
        }
    }

    /**
     * Dossier local sécurisé de stockage des mises à jour téléchargées.
     */
    fun getUpdatesDir(): File {
        val baseDir = if (isWindows) {
            val localAppData = System.getenv("LOCALAPPDATA")
            if (localAppData != null) File(localAppData, "AURA/updates")
            else File(System.getProperty("java.io.tmpdir"), "AURA/updates")
        } else {
            File(System.getProperty("user.home"), ".aura/updates")
        }
        baseDir.mkdirs()
        return baseDir
    }
}
