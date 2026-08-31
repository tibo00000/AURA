package com.aura.music.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Gestionnaire de session et d'authentification sécurisé pour AURA.
 * Stocke les jetons Supabase Auth dans EncryptedSharedPreferences (Keystore AES256-GCM).
 */
class AuthSessionManager(context: Context) {
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

    init {
        loadSession()
    }

    private fun loadSession() {
        val savedToken = prefs.getString(KEY_AUTH_TOKEN, null)
        val savedUserId = prefs.getString(KEY_USER_ID, null)
        val savedEmail = prefs.getString(KEY_USER_EMAIL, null)

        if (!savedToken.isNullOrBlank()) {
            _authToken.value = savedToken
            _userId.value = savedUserId
            _userEmail.value = savedEmail
        } else {
            // TRANSITIONAL SEEDING:
            // Initialise avec l'UUID existant pour garantir la continuité totale des données.
            val initialOwnerUuid = "12345678-1234-1234-1234-1234567890ab"
            _authToken.value = initialOwnerUuid
            _userId.value = initialOwnerUuid
            _userEmail.value = "owner@aura.local"
            saveSession(
                token = initialOwnerUuid,
                refreshToken = null,
                userId = initialOwnerUuid,
                email = "owner@aura.local"
            )
        }
    }

    fun getBearerHeader(): String {
        val token = _authToken.value?.trim().orEmpty()
        return if (token.startsWith("Bearer ", ignoreCase = true)) {
            token
        } else {
            "Bearer $token"
        }
    }

    fun getUserId(): String {
        return _userId.value ?: "12345678-1234-1234-1234-1234567890ab"
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
    }

    fun clearSession() {
        prefs.edit().clear().apply()
        _authToken.value = null
        _userId.value = null
        _userEmail.value = null
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
                INSTANCE ?: AuthSessionManager(context).also { INSTANCE = it }
            }
        }
    }
}
