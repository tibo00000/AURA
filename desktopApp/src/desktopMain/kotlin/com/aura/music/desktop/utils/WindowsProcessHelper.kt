package com.aura.music.desktop.utils

import com.sun.jna.Native
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinError
import com.sun.jna.win32.W32APIOptions
import java.io.File

/**
 * Interface Kernel32 liée explicitement avec les options Unicode (CreateProcessW).
 * Prévient la corruption des chemins système et utilisateurs contenant des caractères accentués.
 */
interface Kernel32Unicode : Kernel32 {
    companion object {
        val INSTANCE: Kernel32Unicode by lazy {
            Native.load("kernel32", Kernel32Unicode::class.java, W32APIOptions.UNICODE_OPTIONS)
        }
    }
}

object WindowsProcessHelper {
    private const val CREATE_NO_WINDOW = 0x08000000
    private const val CREATE_BREAKAWAY_FROM_JOB = 0x01000000

    /**
     * Lance un script batch de façon totalement invisible et détachée du processus parent.
     * Utilise %ComSpec% avec cmd.exe /c pour exécuter le script batch de manière native sous Win32.
     * Tente d'abord le détachement avec CREATE_BREAKAWAY_FROM_JOB, avec fallback gracieux si non autorisé.
     *
     * @return Result.success si le processus a été créé avec succès, Result.failure sinon avec le code d'erreur Win32.
     */
    fun launchHiddenBatch(
        batFile: File,
        args: List<String>,
        workingDir: File
    ): Result<Unit> {
        val comSpec = System.getenv("ComSpec") ?: "C:\\Windows\\System32\\cmd.exe"
        
        // Construction sécurisée de la ligne de commande avec guillemets stricts autour de chaque argument
        val formattedArgs = args.joinToString(" ") { "\"$it\"" }
        val fullCommandLine = "\"$comSpec\" /c \"\"${batFile.absolutePath}\" $formattedArgs\""

        val startupInfo = WinBase.STARTUPINFO().apply {
            cb = WinDef.DWORD(size().toLong()) // Requis impérativement par Win32
        }
        val processInfo = WinBase.PROCESS_INFORMATION()

        val k32 = Kernel32Unicode.INSTANCE

        // 1. Première tentative avec détachement de Job Object
        var flags = CREATE_NO_WINDOW or CREATE_BREAKAWAY_FROM_JOB
        var launched = k32.CreateProcess(
            null,
            fullCommandLine,
            null,
            null,
            false,
            WinDef.DWORD(flags.toLong()),
            null,
            workingDir.absolutePath,
            startupInfo,
            processInfo
        )

        // 2. Fallback sans BREAKAWAY_FROM_JOB si le Job Object parent refuse le breakaway (ERROR_ACCESS_DENIED)
        if (!launched && k32.GetLastError() == WinError.ERROR_ACCESS_DENIED) {
            flags = CREATE_NO_WINDOW
            launched = k32.CreateProcess(
                null,
                fullCommandLine,
                null,
                null,
                false,
                WinDef.DWORD(flags.toLong()),
                null,
                workingDir.absolutePath,
                startupInfo,
                processInfo
            )
        }

        return if (launched) {
            k32.CloseHandle(processInfo.hThread)
            k32.CloseHandle(processInfo.hProcess)
            Result.success(Unit)
        } else {
            val lastError = k32.GetLastError()
            Result.failure(IllegalStateException("Échec de CreateProcessW (code d'erreur Win32: $lastError)"))
        }
    }
}
