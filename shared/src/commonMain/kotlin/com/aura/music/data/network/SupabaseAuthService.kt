package com.aura.music.data.network

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Service d'authentification direct client <-> Supabase Auth (GoTrue REST API).
 *
 * Élimine tout transit d'identifiants ou de mots de passe en clair par le serveur AURA,
 * en conformité avec le principe Zero-Leak (analogue à Spotify OAuth PKCE).
 */
class SupabaseAuthService(
    private val client: HttpClient,
    supabaseUrl: String = BuildConfig.SUPABASE_URL,
    private val anonKey: String = BuildConfig.SUPABASE_ANON_KEY
) {
    private val cleanUrl = supabaseUrl.removeSuffix("/")

    /**
     * Connexion directe avec email et mot de passe.
     * Appelle POST /auth/v1/token?grant_type=password
     */
    suspend fun signInWithPassword(email: String, password: String): Result<SupabaseSessionDto> {
        val trimmedEmail = email.trim().lowercase()
        if (trimmedEmail.isBlank() || password.isBlank()) {
            return Result.failure(IllegalArgumentException("Veuillez renseigner un email et un mot de passe valides."))
        }

        return try {
            val response = client.post("$cleanUrl/auth/v1/token?grant_type=password") {
                header("apikey", anonKey)
                contentType(ContentType.Application.Json)
                setBody(SupabaseAuthRequest(email = trimmedEmail, password = password))
            }

            if (response.status.isSuccess()) {
                val session: SupabaseSessionDto = response.body()
                Result.success(session)
            } else {
                val errorBody = response.bodyAsText()
                val parsedError = runCatching {
                    Json { ignoreUnknownKeys = true }.decodeFromString<SupabaseAuthErrorDto>(errorBody)
                }.getOrNull()

                val errorMessage = parsedError?.errorDescription
                    ?: parsedError?.msg
                    ?: parsedError?.message
                    ?: "Identifiants invalides (HTTP ${response.status.value})"

                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Rafraîchit le jeton d'accès expiré avec un refresh token.
     * Appelle POST /auth/v1/token?grant_type=refresh_token
     */
    suspend fun refreshToken(refreshToken: String): Result<SupabaseSessionDto> {
        if (refreshToken.isBlank()) {
            return Result.failure(IllegalArgumentException("Refresh token manquant."))
        }

        return try {
            val response = client.post("$cleanUrl/auth/v1/token?grant_type=refresh_token") {
                header("apikey", anonKey)
                contentType(ContentType.Application.Json)
                setBody(SupabaseRefreshRequest(refreshToken = refreshToken))
            }

            if (response.status.isSuccess()) {
                val session: SupabaseSessionDto = response.body()
                Result.success(session)
            } else {
                val errorBody = response.bodyAsText()
                Result.failure(Exception("Échec du rafraîchissement de session: $errorBody"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        /**
         * Crée une instance par défaut avec Ktor HttpClient configuré.
         */
        fun createDefault(
            supabaseUrl: String = BuildConfig.SUPABASE_URL,
            anonKey: String = BuildConfig.SUPABASE_ANON_KEY
        ): SupabaseAuthService {
            val json = Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
                prettyPrint = false
                encodeDefaults = true
            }
            val client = HttpClient {
                install(ContentNegotiation) {
                    json(json)
                }
            }
            return SupabaseAuthService(client, supabaseUrl, anonKey)
        }
    }
}
