package com.aura.music.desktop.security

import com.aura.music.data.network.SupabaseAuthService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.Base64

/**
 * État de l'authentification pour le client Desktop.
 */
sealed class DesktopAuthState {
    object Unauthenticated : DesktopAuthState()
    object Authenticating : DesktopAuthState()
    data class Authenticated(val email: String, val userId: String, val token: String) : DesktopAuthState()
    data class Error(val message: String) : DesktopAuthState()
}

/**
 * Gestionnaire de session et d'authentification sécurisé pour le client Desktop AURA.
 * Utilise SupabaseAuthService direct (Zero-Leak) et persiste les tokens via DesktopSecureStorage.
 */
class DesktopAuthSessionManager(
    private val secureStorage: DesktopSecureStorage,
    private val scope: CoroutineScope,
    private val authService: SupabaseAuthService = SupabaseAuthService.createDefault()
) {
    companion object {
        private const val KEY_AUTH_TOKEN = "aura_auth_token"
        private const val KEY_REFRESH_TOKEN = "aura_refresh_token"
        private const val KEY_USER_ID = "aura_user_id"
        private const val KEY_USER_EMAIL = "aura_user_email"
    }

    private val _authState = MutableStateFlow<DesktopAuthState>(DesktopAuthState.Unauthenticated)
    val authState: StateFlow<DesktopAuthState> = _authState.asStateFlow()

    private val _authToken = MutableStateFlow<String?>(null)
    val authToken: StateFlow<String?> = _authToken.asStateFlow()

    private val _userId = MutableStateFlow<String?>(null)
    val userId: StateFlow<String?> = _userId.asStateFlow()

    private val _userEmail = MutableStateFlow<String?>(null)
    val userEmail: StateFlow<String?> = _userEmail.asStateFlow()

    val isAuthenticated: Boolean
        get() = _authState.value is DesktopAuthState.Authenticated

    init {
        loadSession()
    }

    private fun loadSession() {
        val savedToken = secureStorage.getSecret(KEY_AUTH_TOKEN)
        val savedUserId = secureStorage.getSecret(KEY_USER_ID)
        val savedEmail = secureStorage.getSecret(KEY_USER_EMAIL)

        // Nettoyage proactif des tokens mock/fictifs des versions antérieures
        if (!savedToken.isNullOrBlank() &&
            !savedToken.contains("12345678-1234-1234-1234-1234567890ab") &&
            !savedToken.contains("usr_")
        ) {
            val resolvedUserId = savedUserId ?: extractUserIdFromJwt(savedToken) ?: "user"
            val resolvedEmail = savedEmail ?: "user@aura.local"
            _authToken.value = savedToken
            _userId.value = resolvedUserId
            _userEmail.value = resolvedEmail
            _authState.value = DesktopAuthState.Authenticated(
                email = resolvedEmail,
                userId = resolvedUserId,
                token = savedToken
            )
        } else {
            if (savedToken != null && (savedToken.contains("12345678-1234-1234-1234-1234567890ab") || savedToken.contains("usr_"))) {
                logout()
            } else {
                _authToken.value = null
                _userId.value = null
                _userEmail.value = null
                _authState.value = DesktopAuthState.Unauthenticated
            }
        }
    }

    private fun extractUserIdFromJwt(token: String): String? {
        return try {
            val clean = if (token.startsWith("Bearer ", ignoreCase = true)) token.substring(7).trim() else token.trim()
            val parts = clean.split(".")
            if (parts.size >= 2) {
                val payloadB64 = parts[1]
                val padding = when (payloadB64.length % 4) {
                    2 -> "=="
                    3 -> "="
                    else -> ""
                }
                val decodedBytes = Base64.getUrlDecoder().decode(payloadB64 + padding)
                val jsonStr = String(decodedBytes, Charsets.UTF_8)
                val match = Regex("\"sub\"\\s*:\\s*\"([^\"]+)\"").find(jsonStr)
                match?.groupValues?.get(1)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun saveSession(token: String, refreshToken: String?, userId: String, email: String) {
        val bearerToken = if (token.startsWith("Bearer ", ignoreCase = true)) token else "Bearer $token"
        secureStorage.saveSecret(KEY_AUTH_TOKEN, bearerToken)
        refreshToken?.let { secureStorage.saveSecret(KEY_REFRESH_TOKEN, it) }
        secureStorage.saveSecret(KEY_USER_ID, userId)
        secureStorage.saveSecret(KEY_USER_EMAIL, email)

        _authToken.value = bearerToken
        _userId.value = userId
        _userEmail.value = email
    }

    fun getBearerToken(): String? {
        val token = _authToken.value?.trim()
        if (token.isNullOrBlank()) return null
        return if (token.startsWith("Bearer ", ignoreCase = true)) {
            token
        } else {
            "Bearer $token"
        }
    }

    fun getUserId(): String? {
        return _userId.value
    }

    suspend fun loginWithPassword(email: String, password: String): Result<String> = withContext(Dispatchers.IO) {
        _authState.value = DesktopAuthState.Authenticating
        try {
            val trimmedEmail = email.trim().lowercase()
            if (trimmedEmail.isBlank() || password.isBlank()) {
                val err = "Veuillez renseigner un email et un mot de passe valides."
                _authState.value = DesktopAuthState.Error(err)
                return@withContext Result.failure(IllegalArgumentException(err))
            }

            val result = authService.signInWithPassword(trimmedEmail, password)
            result.fold(
                onSuccess = { session ->
                    val cleanToken = session.accessToken.trim()
                    val resolvedUserId = session.user?.id
                        ?: extractUserIdFromJwt(cleanToken)
                        ?: throw IllegalStateException("Impossible de récupérer l'identifiant utilisateur.")
                    val resolvedEmail = session.user?.email ?: trimmedEmail

                    saveSession(cleanToken, session.refreshToken, resolvedUserId, resolvedEmail)

                    val authSuccess = DesktopAuthState.Authenticated(
                        email = resolvedEmail,
                        userId = resolvedUserId,
                        token = cleanToken
                    )
                    _authState.value = authSuccess
                    Result.success(cleanToken)
                },
                onFailure = { error ->
                    val msg = error.message ?: "Erreur d'authentification"
                    _authState.value = DesktopAuthState.Error(msg)
                    Result.failure(error)
                }
            )
        } catch (e: Exception) {
            val err = "Erreur de connexion : ${e.message}"
            _authState.value = DesktopAuthState.Error(err)
            Result.failure(e)
        }
    }

    suspend fun refreshSession(): Boolean = withContext(Dispatchers.IO) {
        val refreshToken = secureStorage.getSecret(KEY_REFRESH_TOKEN)
        if (refreshToken.isNullOrBlank()) {
            logout()
            return@withContext false
        }
        return@withContext try {
            val result = authService.refreshToken(refreshToken)
            result.fold(
                onSuccess = { session ->
                    val cleanToken = session.accessToken.trim()
                    val resolvedUserId = session.user?.id ?: extractUserIdFromJwt(cleanToken) ?: _userId.value ?: "user"
                    val resolvedEmail = session.user?.email ?: _userEmail.value ?: "user@aura.local"

                    saveSession(cleanToken, session.refreshToken, resolvedUserId, resolvedEmail)
                    _authState.value = DesktopAuthState.Authenticated(
                        email = resolvedEmail,
                        userId = resolvedUserId,
                        token = cleanToken
                    )
                    true
                },
                onFailure = { e ->
                    System.err.println("Session refresh failed: ${e.message}. Logging out.")
                    logout()
                    false
                }
            )
        } catch (e: Exception) {
            System.err.println("Session refresh exception: ${e.message}. Logging out.")
            logout()
            false
        }
    }

    fun logout(onLoggedOut: (() -> Unit)? = null) {
        secureStorage.removeSecret(KEY_AUTH_TOKEN)
        secureStorage.removeSecret(KEY_REFRESH_TOKEN)
        secureStorage.removeSecret(KEY_USER_ID)
        secureStorage.removeSecret(KEY_USER_EMAIL)

        _authToken.value = null
        _userId.value = null
        _userEmail.value = null
        _authState.value = DesktopAuthState.Unauthenticated
        onLoggedOut?.invoke()
    }
}
