package com.aura.music.desktop.utils

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.charset.StandardCharsets

/**
 * Garantit qu'une seule instance d'AURA Desktop s'exécute à la fois (Single-Instance Guard).
 *
 * Lorsqu'une seconde instance est lancée (ex: clic sur l'icône épinglée de la barre des tâches
 * alors que la fenêtre principale a été fermée dans la zone de notification / System Tray),
 * elle détecte le verrou système de la première instance, lui envoie un signal IPC local (`SHOW`)
 * pour restaurer la fenêtre au premier plan, puis se termine immédiatement sans créer de doublon.
 */
object DesktopSingleInstanceLock {
    private const val COMMAND_SHOW = "AURA_SHOW_WINDOW"
    private var lockFileRef: RandomAccessFile? = null
    private var lockChannelRef: FileChannel? = null
    private var fileLockRef: FileLock? = null
    private var serverSocketRef: ServerSocket? = null

    private fun getLockDir(): File {
        val dir = File(System.getProperty("user.home"), ".aura")
        dir.mkdirs()
        return dir
    }

    /**
     * Tente d'acquérir le verrou d'instance unique.
     *
     * @param onActivateRequest Callback exécuté dans l'instance primaire lorsqu'une seconde instance
     *                          demande la réouverture et la mise au premier plan de la fenêtre.
     * @return `true` si cette instance est la première (doit continuer le démarrage),
     *         `false` si une instance tourne déjà et a été notifiée (l'appelant doit quitter).
     */
    fun acquireOrNotifyExisting(onActivateRequest: () -> Unit): Boolean {
        val lockDir = getLockDir()
        val lockFile = File(lockDir, "instance.lock")
        val portFile = File(lockDir, "instance.port")

        return try {
            val raf = RandomAccessFile(lockFile, "rw")
            val channel = raf.channel
            val lock = try {
                channel.tryLock()
            } catch (_: Exception) {
                null
            }

            if (lock == null) {
                // Une instance tourne déjà : on lui envoie le signal d'activation
                channel.close()
                raf.close()
                notifyPrimaryInstance(portFile)
                false
            } else {
                // Nous sommes l'instance principale : on conserve les références pour éviter le GC
                lockFileRef = raf
                lockChannelRef = channel
                fileLockRef = lock

                startActivationServer(portFile, onActivateRequest)

                Runtime.getRuntime().addShutdownHook(Thread {
                    release(portFile)
                })
                true
            }
        } catch (e: Exception) {
            // En cas d'erreur imprévue du système de fichiers, ne pas bloquer le lancement de l'application
            System.err.println("Note: Single-instance lock check bypassed: ${e.message}")
            true
        }
    }

    private fun startActivationServer(portFile: File, onActivateRequest: () -> Unit) {
        try {
            val server = ServerSocket(0, 10, InetAddress.getLoopbackAddress())
            serverSocketRef = server
            portFile.writeText(server.localPort.toString(), StandardCharsets.UTF_8)

            Thread({
                while (!server.isClosed) {
                    try {
                        val client = server.accept()
                        client.use { sock ->
                            sock.soTimeout = 1500
                            val line = BufferedReader(
                                InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8)
                            ).readLine()
                            if (line == COMMAND_SHOW) {
                                onActivateRequest()
                            }
                        }
                    } catch (_: Exception) {
                        if (server.isClosed) break
                    }
                }
            }, "Aura-SingleInstance-Listener").apply {
                isDaemon = true
                start()
            }
        } catch (e: Exception) {
            System.err.println("Note: Could not start single-instance IPC server: ${e.message}")
        }
    }

    private fun notifyPrimaryInstance(portFile: File): Boolean {
        if (!portFile.exists()) return false
        return try {
            val port = portFile.readText(StandardCharsets.UTF_8).trim().toIntOrNull() ?: return false
            Socket().use { socket ->
                socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1000)
                val writer = OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
                writer.write("$COMMAND_SHOW\n")
                writer.flush()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun release(portFile: File) {
        try {
            serverSocketRef?.close()
        } catch (_: Exception) {}
        try {
            fileLockRef?.release()
        } catch (_: Exception) {}
        try {
            lockChannelRef?.close()
        } catch (_: Exception) {}
        try {
            lockFileRef?.close()
        } catch (_: Exception) {}
        try {
            if (portFile.exists()) portFile.delete()
        } catch (_: Exception) {}
    }
}
