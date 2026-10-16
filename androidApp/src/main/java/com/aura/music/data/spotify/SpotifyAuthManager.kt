package com.aura.music.data.spotify

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.aura.music.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Gestionnaire d'authentification Spotify conforme OAuth 2.0 PKCE.
 * 
 * - Zéro secret client hardcodé dans l'application.
 * - Stockage des tokens sécurisé via EncryptedSharedPreferences (Android Keystore, AES-256).
 * - Rotation systématique du refresh_token.
 * - Purge propre et ré-authentification en cas d'expiration/révocation de session.
 */
class SpotifyAuthManager(private val context: Context) {

    companion object {
        private const val TAG = "SpotifyAuthManager"
        private const val PREFS_FILENAME = "aura_spotify_secure_prefs"
        private const val KEY_ACCESS_TOKEN = "spotify_access_token"
        private const val KEY_REFRESH_TOKEN = "spotify_refresh_token"
        private const val KEY_EXPIRES_AT = "spotify_token_expires_at"
        private const val KEY_CODE_VERIFIER = "spotify_code_verifier"

        const val REDIRECT_URI = "aura://spotify-callback"
        const val SCOPES = "playlist-read-private playlist-read-collaborative user-library-read"
    }

    private val securePrefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                PREFS_FILENAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "Erreur initialisation EncryptedSharedPreferences, fallback standard avec warning", e)
            context.getSharedPreferences(PREFS_FILENAME, Context.MODE_PRIVATE)
        }
    }

    private val _isConnected = MutableStateFlow(hasRefreshToken())
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private fun hasRefreshToken(): Boolean {
        return !securePrefs.getString(KEY_REFRESH_TOKEN, null).isNullOrBlank()
    }

    /**
     * Lance le flux d'autorisation OAuth 2.0 PKCE via Chrome Custom Tabs.
     */
    fun startAuthFlow(activityContext: Context) {
        val clientId = BuildConfig.SPOTIFY_CLIENT_ID
        if (clientId.isBlank()) {
            Log.e(TAG, "SPOTIFY_CLIENT_ID manquant dans BuildConfig/local.properties")
            return
        }

        val codeVerifier = generateCodeVerifier()
        val codeChallenge = generateCodeChallenge(codeVerifier)

        // Sauvegarde temporaire du code_verifier chiffré
        securePrefs.edit().putString(KEY_CODE_VERIFIER, codeVerifier).apply()

        val authUrl = Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", codeChallenge)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("show_dialog", "true")
            .build()

        Log.i(TAG, "Lancement du flux Spotify Auth PKCE...")
        val customTabsIntent = CustomTabsIntent.Builder().setShowTitle(true).build()
        try {
            customTabsIntent.launchUrl(activityContext, authUrl)
        } catch (e: Exception) {
            Log.e(TAG, "Impossible de lancer CustomTabs, ouverture par Intent standard", e)
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, authUrl)
            activityContext.startActivity(intent)
        }
    }

    /**
     * Traite le Deep Link reçu aura://spotify-callback?code=...
     */
    suspend fun handleAuthCallback(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        if (uri.scheme != "aura" || uri.host != "spotify-callback") {
            return@withContext false
        }

        val error = uri.getQueryParameter("error")
        if (error != null) {
            Log.e(TAG, "Erreur reçue de Spotify OAuth : $error")
            return@withContext false
        }

        val code = uri.getQueryParameter("code")
        if (code.isNullOrBlank()) {
            Log.e(TAG, "Code d'autorisation manquant dans le Deep Link")
            return@withContext false
        }

        val codeVerifier = securePrefs.getString(KEY_CODE_VERIFIER, null)
        if (codeVerifier.isNullOrBlank()) {
            Log.e(TAG, "code_verifier introuvable dans les préférences sécurisées")
            return@withContext false
        }

        exchangeCodeForTokens(code, codeVerifier)
    }

    private suspend fun exchangeCodeForTokens(code: String, codeVerifier: String): Boolean = withContext(Dispatchers.IO) {
        val clientId = BuildConfig.SPOTIFY_CLIENT_ID
        try {
            val url = URL("https://accounts.spotify.com/api/token")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connectTimeout = 10_000
                readTimeout = 10_000
            }

            val body = listOf(
                "grant_type" to "authorization_code",
                "client_id" to clientId,
                "code" to code,
                "redirect_uri" to REDIRECT_URI,
                "code_verifier" to codeVerifier
            ).joinToString("&") { (k, v) ->
                "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
            }

            OutputStreamWriter(connection.outputStream).use { it.write(body) }

            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(responseText)
                val accessToken = json.getString("access_token")
                val refreshToken = json.optString("refresh_token", "")
                val expiresIn = json.optLong("expires_in", 3600L)

                saveTokens(accessToken, refreshToken.takeIf { it.isNotBlank() }, expiresIn)
                _isConnected.value = true
                Log.i(TAG, "Compte Spotify lié avec succès (tokens enregistrés) !")
                return@withContext true
            } else {
                val err = connection.errorStream?.bufferedReader()?.use { it.readText() }
                Log.e(TAG, "Échec échange code Spotify ($responseCode) : $err")
                return@withContext false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception lors de l'échange de token Spotify", e)
            return@withContext false
        }
    }

    /**
     * Retourne un access_token valide (avec rafraîchissement transparent et rotation du refresh_token).
     * En cas d'expiration/révocation irrécupérable, purge les données et bascule isConnected à false.
     */
    suspend fun getValidAccessToken(): String? = withContext(Dispatchers.IO) {
        val refreshToken = securePrefs.getString(KEY_REFRESH_TOKEN, null)
        if (refreshToken.isNullOrBlank()) {
            _isConnected.value = false
            return@withContext null
        }

        val cachedAccessToken = securePrefs.getString(KEY_ACCESS_TOKEN, null)
        val expiresAt = securePrefs.getLong(KEY_EXPIRES_AT, 0L)

        // Si le token actuel est encore valide (avec 60s de marge de sécurité)
        if (!cachedAccessToken.isNullOrBlank() && expiresAt > System.currentTimeMillis() + 60_000L) {
            return@withContext cachedAccessToken
        }

        // Rafraîchissement requis
        return@withContext refreshAccessToken(refreshToken)
    }

    private suspend fun refreshAccessToken(refreshToken: String): String? = withContext(Dispatchers.IO) {
        val clientId = BuildConfig.SPOTIFY_CLIENT_ID
        try {
            val url = URL("https://accounts.spotify.com/api/token")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connectTimeout = 10_000
                readTimeout = 10_000
            }

            val body = listOf(
                "grant_type" to "refresh_token",
                "client_id" to clientId,
                "refresh_token" to refreshToken
            ).joinToString("&") { (k, v) ->
                "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
            }

            OutputStreamWriter(connection.outputStream).use { it.write(body) }

            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(responseText)
                val newAccessToken = json.getString("access_token")
                // Rotation du refresh_token : si Spotify en renvoie un nouveau, on l'écrase systématiquement
                val rotatedRefreshToken = json.optString("refresh_token", "").takeIf { it.isNotBlank() }
                val expiresIn = json.optLong("expires_in", 3600L)

                saveTokens(newAccessToken, rotatedRefreshToken, expiresIn)
                _isConnected.value = true
                Log.d(TAG, "Access token Spotify rafraîchi avec succès !")
                return@withContext newAccessToken
            } else {
                val err = connection.errorStream?.bufferedReader()?.use { it.readText() }
                Log.e(TAG, "Échec refresh token Spotify ($responseCode) : $err")
                // Si la session est révoquée/invalide
                if (responseCode == 400 || responseCode == 401) {
                    disconnectSpotify()
                }
                return@withContext null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception lors du rafraîchissement du token Spotify", e)
            return@withContext null
        }
    }

    private fun saveTokens(accessToken: String, newRefreshToken: String?, expiresInSeconds: Long) {
        val expiresAt = System.currentTimeMillis() + (expiresInSeconds - 60L).coerceAtLeast(0L) * 1000L
        val editor = securePrefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putLong(KEY_EXPIRES_AT, expiresAt)
            .remove(KEY_CODE_VERIFIER)

        if (!newRefreshToken.isNullOrBlank()) {
            editor.putString(KEY_REFRESH_TOKEN, newRefreshToken)
        }

        editor.apply()
    }

    /**
     * Déconnecte le compte Spotify et purge le stockage chiffré.
     */
    fun disconnectSpotify() {
        securePrefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EXPIRES_AT)
            .remove(KEY_CODE_VERIFIER)
            .apply()
        _isConnected.value = false
        Log.i(TAG, "Compte Spotify déconnecté et tokens purgés.")
    }

    // ==========================================
    // Utilitaires Cryptographiques OAuth PKCE
    // ==========================================

    private fun generateCodeVerifier(): String {
        val secureRandom = SecureRandom()
        val code = ByteArray(64)
        secureRandom.nextBytes(code)
        return Base64.encodeToString(code, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val bytes = verifier.toByteArray(Charsets.US_ASCII)
        val messageDigest = MessageDigest.getInstance("SHA-256")
        messageDigest.update(bytes, 0, bytes.size)
        val digest = messageDigest.digest()
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
