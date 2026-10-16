package com.aura.music.data.network

import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private fun jsAtob(encoded: String): String = js("window.atob(encoded)")
private fun jsCurrentTimeSeconds(): Double = js("Date.now() / 1000.0")

@Serializable
private data class JwtPayload(
    val exp: Long? = null,
    val sub: String? = null,
    val email: String? = null
)

/**
 * Gestionnaire de session et d'authentification pour le client Web AURA (Kotlin/Wasm).
 *
 * Utilise localStorage pour persister les tokens Supabase Auth, gère le rafraîchissement
 * automatique via SupabaseAuthService, et écoute les événements de stockage inter-onglets.
 */
class WebAuthSessionManager(
    private val authService: SupabaseAuthService = SupabaseAuthService.createDefault()
) {
    private val jsonParser = Json { ignoreUnknownKeys = true }

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
        try {
            window.addEventListener("storage", { _ ->
                loadSession()
            })
        } catch (_: Throwable) {
            // Ignorer si environnement non DOM
        }
    }

    fun loadSession() {
        val savedToken = localStorage.getItem(KEY_AUTH_TOKEN)
        val savedUserId = localStorage.getItem(KEY_USER_ID)
        val savedEmail = localStorage.getItem(KEY_USER_EMAIL)

        if (!savedToken.isNullOrBlank() && !savedUserId.isNullOrBlank()) {
            _authToken.value = savedToken
            _userId.value = savedUserId
            _userEmail.value = savedEmail
            _isLoggedIn.value = true
        } else {
            _authToken.value = null
            _userId.value = null
            _userEmail.value = null
            _isLoggedIn.value = false
        }
    }

    private fun extractExpAndUserIdFromJwt(token: String): Pair<Double?, String?> {
        return try {
            val parts = token.split(".")
            if (parts.size != 3) return Pair(null, null)
            val base64Url = parts[1]
            val base64 = base64Url.replace('-', '+').replace('_', '/')
            val padded = base64 + "=".repeat((4 - base64.length % 4) % 4)
            val jsonStr = jsAtob(padded)
            val payload = jsonParser.decodeFromString<JwtPayload>(jsonStr)
            Pair(payload.exp?.toDouble(), payload.sub)
        } catch (_: Throwable) {
            Pair(null, null)
        }
    }

    fun isTokenExpired(): Boolean {
        val token = _authToken.value ?: return true
        val (expSeconds, _) = extractExpAndUserIdFromJwt(token)
        if (expSeconds == null) return false
        val now = jsCurrentTimeSeconds()
        // Marge de sécurité de 60 secondes
        return now >= (expSeconds - 60.0)
    }

    suspend fun login(email: String, password: String): Result<Unit> {
        return try {
            val result = authService.signInWithPassword(email, password)
            result.fold(
                onSuccess = { session ->
                    val (_, sub) = extractExpAndUserIdFromJwt(session.accessToken)
                    val resolvedUserId = session.user?.id ?: sub
                        ?: return Result.failure(IllegalStateException("Identifiant utilisateur introuvable."))
                    val resolvedEmail = session.user?.email ?: email.trim().lowercase()

                    saveSession(
                        token = session.accessToken,
                        refreshToken = session.refreshToken,
                        userId = resolvedUserId,
                        email = resolvedEmail
                    )
                    Result.success(Unit)
                },
                onFailure = { error ->
                    Result.failure(error)
                }
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun refreshSession(): Result<Unit> {
        val refreshToken = localStorage.getItem(KEY_REFRESH_TOKEN)
        if (refreshToken.isNullOrBlank()) {
            logout()
            return Result.failure(IllegalStateException("Aucun jeton de rafraîchissement disponible."))
        }

        return try {
            val result = authService.refreshToken(refreshToken)
            result.fold(
                onSuccess = { session ->
                    val (_, sub) = extractExpAndUserIdFromJwt(session.accessToken)
                    val resolvedUserId = session.user?.id ?: sub ?: _userId.value ?: ""
                    val resolvedEmail = session.user?.email ?: _userEmail.value

                    saveSession(
                        token = session.accessToken,
                        refreshToken = session.refreshToken ?: refreshToken,
                        userId = resolvedUserId,
                        email = resolvedEmail
                    )
                    Result.success(Unit)
                },
                onFailure = { error ->
                    logout()
                    Result.failure(error)
                }
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun ensureValidToken(): String? {
        val currentToken = _authToken.value
        if (currentToken.isNullOrBlank()) return null
        if (!isTokenExpired()) return currentToken

        val refreshResult = refreshSession()
        return if (refreshResult.isSuccess) {
            _authToken.value
        } else {
            null
        }
    }

    fun logout() {
        localStorage.removeItem(KEY_AUTH_TOKEN)
        localStorage.removeItem(KEY_REFRESH_TOKEN)
        localStorage.removeItem(KEY_USER_ID)
        localStorage.removeItem(KEY_USER_EMAIL)

        _authToken.value = null
        _userId.value = null
        _userEmail.value = null
        _isLoggedIn.value = false
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

        localStorage.setItem(KEY_AUTH_TOKEN, cleanToken)
        if (refreshToken != null) {
            localStorage.setItem(KEY_REFRESH_TOKEN, refreshToken)
        }
        localStorage.setItem(KEY_USER_ID, userId)
        if (email != null) {
            localStorage.setItem(KEY_USER_EMAIL, email)
        }

        _authToken.value = cleanToken
        _userId.value = userId
        _userEmail.value = email
        _isLoggedIn.value = cleanToken.isNotBlank() && userId.isNotBlank()
    }

    companion object {
        private const val KEY_AUTH_TOKEN = "aura_auth_token"
        private const val KEY_REFRESH_TOKEN = "aura_refresh_token"
        private const val KEY_USER_ID = "aura_user_id"
        private const val KEY_USER_EMAIL = "aura_user_email"

        private var instance: WebAuthSessionManager? = null

        fun getInstance(): WebAuthSessionManager {
            return instance ?: WebAuthSessionManager().also { instance = it }
        }
    }
}
