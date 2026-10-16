package com.aura.music.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.aura.music.data.network.SupabaseAuthService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Gestionnaire de session et d'authentification sécurisé pour AURA.
 * Stocke les jetons Supabase Auth dans EncryptedSharedPreferences (Keystore AES256-GCM).
 */
class AuthSessionManager(
    context: Context,
    private val authService: SupabaseAuthService = SupabaseAuthService.createDefault()
) {
    private val appContext = context.applicationContext

    private val prefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                appContext,
                "aura_auth_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "EncryptedSharedPreferences failed, falling back to private prefs", e)
            appContext.getSharedPreferences("aura_auth_prefs", Context.MODE_PRIVATE)
        }
    }

    private val _authToken = MutableStateFlow<String?>(null)
    val authToken: StateFlow<String?> = _authToken.asStateFlow()

    private val _userId = MutableStateFlow<String?>(null)
    val userId: StateFlow<String?> = _userId.asStateFlow()

    private val _userEmail = MutableStateFlow<String?>(null)
    val userEmail: StateFlow<String?> = _userEmail.asStateFlow()

    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    init {
        loadSession()
    }

    private fun loadSession() {
        val savedToken = prefs.getString(KEY_AUTH_TOKEN, null)
        val savedUserId = prefs.getString(KEY_USER_ID, null)
        val savedEmail = prefs.getString(KEY_USER_EMAIL, null)

        if (!savedToken.isNullOrBlank() && !savedUserId.isNullOrBlank()) {
            _authToken.value = savedToken
            _userId.value = savedUserId
            _userEmail.value = savedEmail
            _isLoggedIn.value = true
        } else {
            // New installations start unauthenticated
            _authToken.value = null
            _userId.value = null
            _userEmail.value = null
            _isLoggedIn.value = false
        }
    }

    private fun extractUserIdFromJwt(token: String): String? {
        return try {
            val parts = token.split(".")
            if (parts.size == 3) {
                val payloadB64 = parts[1]
                val padded = payloadB64 + "=".repeat((-payloadB64.length % 4 + 4) % 4)
                val decodedBytes = android.util.Base64.decode(padded, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
                val jsonStr = String(decodedBytes, Charsets.UTF_8)
                val json = org.json.JSONObject(jsonStr)
                json.optString("sub").takeIf { it.isNotBlank() }
            } else null
        } catch (e: Exception) {
            null
        }
    }

    suspend fun login(email: String, password: String): Result<Unit> {
        return try {
            val result = authService.signInWithPassword(email, password)
            result.map { session ->
                val resolvedUserId = session.user?.id
                    ?: extractUserIdFromJwt(session.accessToken)
                    ?: throw IllegalStateException("Impossible de récupérer l'identifiant utilisateur.")
                val resolvedEmail = session.user?.email ?: email.trim().lowercase()

                saveSession(
                    token = session.accessToken,
                    refreshToken = session.refreshToken,
                    userId = resolvedUserId,
                    email = resolvedEmail
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun refreshSession(): Result<Unit> {
        val currentRefreshToken = prefs.getString(KEY_REFRESH_TOKEN, null)
        if (currentRefreshToken.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Aucun jeton de rafraîchissement disponible."))
        }
        return try {
            val result = authService.refreshToken(currentRefreshToken)
            result.map { session ->
                val resolvedUserId = session.user?.id
                    ?: extractUserIdFromJwt(session.accessToken)
                    ?: _userId.value
                    ?: throw IllegalStateException("Identifiant utilisateur introuvable après rafraîchissement.")
                val resolvedEmail = session.user?.email ?: _userEmail.value

                saveSession(
                    token = session.accessToken,
                    refreshToken = session.refreshToken ?: currentRefreshToken,
                    userId = resolvedUserId,
                    email = resolvedEmail
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun logout() {
        clearSession()
    }

    fun getBearerHeader(): String {
        val token = _authToken.value?.trim().orEmpty()
        return if (token.isEmpty()) {
            ""
        } else if (token.startsWith("Bearer ", ignoreCase = true)) {
            token
        } else {
            "Bearer $token"
        }
    }

    fun getUserId(): String {
        return _userId.value.orEmpty()
    }

    fun saveSession(
        token: String,
        refreshToken: String?,
        userId: String,
        email: String?
    ) {
        val cleanToken = if (token.startsWith("Bearer ", ignoreCase = true)) {
            token.substring(7).trim()
        } else {
            token.trim()
        }

        prefs.edit()
            .putString(KEY_AUTH_TOKEN, cleanToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putString(KEY_USER_ID, userId)
            .putString(KEY_USER_EMAIL, email)
            .apply()

        _authToken.value = cleanToken
        _userId.value = userId
        _userEmail.value = email
        _isLoggedIn.value = cleanToken.isNotBlank() && userId.isNotBlank()
    }

    fun clearSession() {
        prefs.edit().clear().apply()
        _authToken.value = null
        _userId.value = null
        _userEmail.value = null
        _isLoggedIn.value = false
    }

    companion object {
        private const val TAG = "AuthSessionManager"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USER_EMAIL = "user_email"

        @Volatile
        private var INSTANCE: AuthSessionManager? = null

        fun getInstance(context: Context): AuthSessionManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AuthSessionManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
