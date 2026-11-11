package com.aura.music.core

import com.aura.music.data.network.BuildConfig
import com.aura.music.data.network.KtorAuraApiService
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.plugin
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.coroutines.cancel

/** Android-only transport. Mutation bodies are never automatically replayed. */
internal fun authenticatedApi(auth: AuthSessionManager): KtorAuraApiService {
    val client = HttpClient {
        followRedirects = false
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true }) }
        install(HttpTimeout) {
            requestTimeoutMillis = 300_000; connectTimeoutMillis = 30_000; socketTimeoutMillis = 120_000
        }
    }
    installSessionAuthorization(client, auth, Url(BuildConfig.API_BASE_URL))
    return KtorAuraApiService(client)
}

internal fun installSessionAuthorization(client: HttpClient, auth: AuthSessionManager, base: Url) {
    client.plugin(HttpSend).intercept { request ->
        if (request.headers[HttpHeaders.Authorization] == null) return@intercept execute(request)
        val origin = request.url.build() // Normalize an implicit HTTPS port before comparing origins.
        if (origin.host != base.host || origin.port != base.port || origin.protocol != base.protocol) {
            throw SessionUnavailableException()
        }
        val lease = auth.authorize(request.headers[HttpHeaders.Authorization])
        if (!auth.isCurrent(lease)) throw SessionUnavailableException()
        request.headers[HttpHeaders.Authorization] = lease.bearer
        val call = execute(request)
        if (!auth.isCurrent(lease)) { call.cancel(); throw SessionUnavailableException() }
        if (call.response.status == HttpStatusCode.Unauthorized && request.method in setOf(HttpMethod.Get, HttpMethod.Head)) {
            call.cancel()
            val renewed = auth.validSession(lease)
            if (!auth.isCurrent(renewed)) throw SessionUnavailableException()
            request.headers[HttpHeaders.Authorization] = renewed.bearer
            val retry = execute(request)
            if (!auth.isCurrent(renewed)) { retry.cancel(); throw SessionUnavailableException() }
            retry
        } else call
    }
}
