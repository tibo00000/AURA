package com.aura.music.desktop.security

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Interface pour le stockage sécurisé des jetons et secrets sur Desktop.
 */
interface DesktopSecureStorage {
    fun saveSecret(key: String, value: String)
    fun getSecret(key: String): String?
    fun removeSecret(key: String)

    companion object {
        fun createDefault(): DesktopSecureStorage {
            val osName = System.getProperty("os.name", "").lowercase()
            return if (osName.contains("win")) {
                try {
                    WindowsDpapiSecureStorage()
                } catch (e: Throwable) {
                    System.err.println("DPAPI non disponible, utilisation du KeyStore sécurisé: ${e.message}")
                    JksSecureStorage()
                }
            } else {
                JksSecureStorage()
            }
        }
    }
}

/**
 * Implémentation Windows utilisant l'API native DPAPI (Data Protection API) via JNA.
 * Les données sont chiffrées avec la clé privée de la session Windows de l'utilisateur connecté.
 */
class WindowsDpapiSecureStorage : DesktopSecureStorage {
    private val storageDir = File(System.getProperty("user.home"), ".aura/secure").apply { mkdirs() }
    private val storageFile = File(storageDir, "tokens.dpapi")

    @Synchronized
    override fun saveSecret(key: String, value: String) {
        val current = loadAll()
        current[key] = value
        saveAll(current)
    }

    @Synchronized
    override fun getSecret(key: String): String? {
        val current = loadAll()
        return current[key]
    }

    @Synchronized
    override fun removeSecret(key: String) {
        val current = loadAll()
        if (current.remove(key) != null) {
            saveAll(current)
        }
    }

    private fun loadAll(): MutableMap<String, String> {
        if (!storageFile.exists()) return mutableMapOf()
        return try {
            val encryptedBytes = storageFile.readBytes()
            if (encryptedBytes.isEmpty()) return mutableMapOf()
            val decryptedBytes = com.sun.jna.platform.win32.Crypt32Util.cryptUnprotectData(encryptedBytes)
            val props = Properties().apply { load(java.io.ByteArrayInputStream(decryptedBytes)) }
            props.entries.associate { it.key.toString() to it.value.toString() }.toMutableMap()
        } catch (e: Exception) {
            System.err.println("Erreur de déchiffrement DPAPI: ${e.message}")
            mutableMapOf()
        }
    }

    private fun saveAll(map: Map<String, String>) {
        try {
            val props = Properties().apply {
                map.forEach { (k, v) -> setProperty(k, v) }
            }
            val baos = java.io.ByteArrayOutputStream()
            props.store(baos, "AURA DPAPI Secure Tokens")
            val plainBytes = baos.toByteArray()
            val encryptedBytes = com.sun.jna.platform.win32.Crypt32Util.cryptProtectData(plainBytes)
            storageFile.writeBytes(encryptedBytes)
        } catch (e: Exception) {
            System.err.println("Erreur de chiffrement DPAPI: ${e.message}")
        }
    }
}

/**
 * Implémentation multiplateforme (macOS, Linux, fallback) utilisant un Java KeyStore (PKCS12)
 * et du chiffrement AES-256-GCM avec permissions de fichiers strictes réservées à l'utilisateur (0600).
 */
class JksSecureStorage : DesktopSecureStorage {
    private val storageDir = File(System.getProperty("user.home"), ".aura/secure").apply {
        mkdirs()
        protectDirectory(this)
    }
    private val keystoreFile = File(storageDir, "vault.p12")
    private val dataFile = File(storageDir, "tokens.enc")
    private val ksPassword = "aura_internal_keystore_pass".toCharArray()
    private val keyAlias = "aura_master_key"

    init {
        initKeyStoreIfNeeded()
    }

    private fun initKeyStoreIfNeeded() {
        val ks = KeyStore.getInstance("PKCS12")
        if (keystoreFile.exists()) {
            keystoreFile.inputStream().use { ks.load(it, ksPassword) }
        } else {
            ks.load(null, ksPassword)
            val keyGen = KeyGenerator.getInstance("AES")
            keyGen.init(256, SecureRandom())
            val secretKey = keyGen.generateKey()
            ks.setEntry(
                keyAlias,
                KeyStore.SecretKeyEntry(secretKey),
                KeyStore.PasswordProtection(ksPassword)
            )
            keystoreFile.outputStream().use { ks.store(it, ksPassword) }
            protectFile(keystoreFile)
        }
    }

    private fun getSecretKey(): SecretKey {
        val ks = KeyStore.getInstance("PKCS12")
        keystoreFile.inputStream().use { ks.load(it, ksPassword) }
        val entry = ks.getEntry(keyAlias, KeyStore.PasswordProtection(ksPassword)) as KeyStore.SecretKeyEntry
        return entry.secretKey
    }

    @Synchronized
    override fun saveSecret(key: String, value: String) {
        val current = loadAll()
        current[key] = value
        saveAll(current)
    }

    @Synchronized
    override fun getSecret(key: String): String? {
        val current = loadAll()
        return current[key]
    }

    @Synchronized
    override fun removeSecret(key: String) {
        val current = loadAll()
        if (current.remove(key) != null) {
            saveAll(current)
        }
    }

    private fun loadAll(): MutableMap<String, String> {
        if (!dataFile.exists()) return mutableMapOf()
        return try {
            val content = dataFile.readText(StandardCharsets.UTF_8).trim()
            if (content.isEmpty()) return mutableMapOf()
            val raw = Base64.getDecoder().decode(content)
            if (raw.size < 12 + 16) return mutableMapOf() // IV (12) + Tag (16) minimum

            val iv = raw.copyOfRange(0, 12)
            val cipherText = raw.copyOfRange(12, raw.size)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), GCMParameterSpec(128, iv))
            val plainBytes = cipher.doFinal(cipherText)

            val props = Properties().apply { load(java.io.ByteArrayInputStream(plainBytes)) }
            props.entries.associate { it.key.toString() to it.value.toString() }.toMutableMap()
        } catch (e: Exception) {
            System.err.println("Erreur de déchiffrement JKS: ${e.message}")
            mutableMapOf()
        }
    }

    private fun saveAll(map: Map<String, String>) {
        try {
            val props = Properties().apply {
                map.forEach { (k, v) -> setProperty(k, v) }
            }
            val baos = java.io.ByteArrayOutputStream()
            props.store(baos, "AURA JKS Encrypted Tokens")
            val plainBytes = baos.toByteArray()

            val iv = ByteArray(12).apply { SecureRandom().nextBytes(this) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getSecretKey(), GCMParameterSpec(128, iv))
            val cipherText = cipher.doFinal(plainBytes)

            val combined = ByteArray(iv.size + cipherText.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(cipherText, 0, combined, iv.size, cipherText.size)

            dataFile.writeText(Base64.getEncoder().encodeToString(combined), StandardCharsets.UTF_8)
            protectFile(dataFile)
        } catch (e: Exception) {
            System.err.println("Erreur de chiffrement JKS: ${e.message}")
        }
    }

    private fun protectDirectory(dir: File) {
        try {
            dir.setReadable(true, true)
            dir.setWritable(true, true)
            dir.setExecutable(true, true)
            if (System.getProperty("os.name", "").lowercase().contains("nix") ||
                System.getProperty("os.name", "").lowercase().contains("nux") ||
                System.getProperty("os.name", "").lowercase().contains("mac")
            ) {
                val perms = setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE
                )
                Files.setPosixFilePermissions(dir.toPath(), perms)
            }
        } catch (_: Throwable) {}
    }

    private fun protectFile(file: File) {
        try {
            file.setReadable(true, true)
            file.setWritable(true, true)
            if (System.getProperty("os.name", "").lowercase().contains("nix") ||
                System.getProperty("os.name", "").lowercase().contains("nux") ||
                System.getProperty("os.name", "").lowercase().contains("mac")
            ) {
                val perms = setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE
                )
                Files.setPosixFilePermissions(file.toPath(), perms)
            }
        } catch (_: Throwable) {}
    }
}
