package com.aura.music.core

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.aura.music.data.network.SupabaseAuthService
import com.aura.music.data.network.SupabaseSessionDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.util.Base64

internal data class StoredSession(val access: String, val refresh: String?, val userId: String, val email: String?) {
    override fun toString() = "StoredSession(redacted)"
}
internal interface SessionStore {
    fun read(): StoredSession?
    fun write(session: StoredSession?)
}

/** One preference value makes the rotated pair indivisible, including legacy upgrades. */
internal class EncryptedSessionStore(private val preferences: () -> SharedPreferences) : SessionStore {
    constructor(context: Context) : this({
        val key = MasterKey.Builder(context.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(context.applicationContext, "aura_auth_secure_prefs", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    })
    private val prefs by lazy(preferences)
    override fun read(): StoredSession? {
        val value = prefs.getString("session_pair", null)
        if (value != null) {
            val obj = Json.parseToJsonElement(value).jsonObject
            return StoredSession(obj.getValue("access").jsonPrimitive.content,
                obj["refresh"]?.jsonPrimitive?.contentOrNull, obj.getValue("user").jsonPrimitive.content,
                obj["email"]?.jsonPrimitive?.contentOrNull)
        }
        val access = prefs.getString("auth_token", null) ?: return null
        val user = prefs.getString("user_id", null) ?: return null
        return StoredSession(access, prefs.getString("refresh_token", null), user, prefs.getString("user_email", null))
    }
    override fun write(session: StoredSession?) {
        val editor = prefs.edit()
        if (session == null) editor.remove("session_pair") else editor.putString("session_pair", buildJsonObject {
            put("access", session.access); put("refresh", session.refresh?.let(::JsonPrimitive) ?: JsonNull)
            put("user", session.userId); put("email", session.email?.let(::JsonPrimitive) ?: JsonNull)
        }.toString())
        // A single editor transaction, no credentials in logs or exceptions.
        editor.remove("auth_token").remove("refresh_token").remove("user_id").remove("user_email")
        // Refresh/login run on IO; don't return a new access token before its rotated pair is durable.
        if (session == null) editor.apply() else check(editor.commit()) { "Session non persistée" }
    }
}

class SessionUnavailableException : java.io.IOException("Session indisponible : réessayer ou se reconnecter.")

class AuthSessionManager internal constructor(
    private val store: SessionStore,
    private val signIn: suspend (String, String) -> Result<SupabaseSessionDto>,
    private val refresh: suspend (String) -> Result<SupabaseSessionDto>,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 }
) {
    constructor(context: Context, authService: SupabaseAuthService = SupabaseAuthService.createDefault()) :
        this(EncryptedSessionStore(context), authService::signInWithPassword, authService::refreshToken)

    class Lease internal constructor(val bearer: String, val userId: String, internal val epoch: Long) {
        override fun toString() = "SessionLease(redacted)"
    }
    class Scope internal constructor(val userId: String, internal val epoch: Long) {
        override fun equals(other: Any?) = other is Scope && userId == other.userId && epoch == other.epoch
        override fun hashCode() = 31 * userId.hashCode() + epoch.hashCode()
        override fun toString() = "SessionScope(redacted)"
    }
    private val stateLock = Any()
    private val refreshLock = Mutex()
    @Volatile private var epoch = 0L
    @Volatile private var session: StoredSession? = runCatching { store.read() }.getOrNull()
    private val _sessionScope = MutableStateFlow(session?.let { Scope(it.userId, epoch) })
    internal val sessionScope = _sessionScope.asStateFlow()
    private val _authToken = MutableStateFlow(session?.access)
    val authToken = _authToken.asStateFlow()
    private val _userId = MutableStateFlow(session?.userId)
    val userId = _userId.asStateFlow()
    private val _userEmail = MutableStateFlow(session?.email)
    val userEmail = _userEmail.asStateFlow()
    private val _isLoggedIn = MutableStateFlow(session != null)
    val isLoggedIn = _isLoggedIn.asStateFlow()

    private fun claims(token: String): JsonObject? = runCatching {
        val raw = token.removePrefix("Bearer ").split('.')[1]
        Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(raw), Charsets.UTF_8)).jsonObject
    }.getOrNull()
    private fun unexpired(token: String) = (claims(token)?.get("exp")?.jsonPrimitive?.longOrNull ?: 0) > nowSeconds() + 30

    private fun publish(value: StoredSession?) {
        store.write(value)
        session = value
        _authToken.value = value?.access; _userId.value = value?.userId; _userEmail.value = value?.email
        _isLoggedIn.value = value != null
        _sessionScope.value = value?.let { Scope(it.userId, epoch) }
    }
    private fun advanceGeneration(): Long {
        epoch++
        _sessionScope.value = session?.let { Scope(it.userId, epoch) }
        return epoch
    }
    fun saveSession(token: String, refreshToken: String?, userId: String, email: String?) = synchronized(stateLock) {
        advanceGeneration()
        publish(StoredSession(token.removePrefix("Bearer ").trim(), refreshToken, userId, email))
    }
    fun clearSession() = synchronized(stateLock) { advanceGeneration(); publish(null) }
    fun logout() = clearSession()
    fun getBearerHeader(): String = session?.access?.let { "Bearer $it" }.orEmpty()
    fun getUserId(): String = session?.userId.orEmpty()
    fun isCurrent(lease: Lease): Boolean = synchronized(stateLock) { epoch == lease.epoch && session?.userId == lease.userId }
    internal fun captureScope(): Scope = synchronized(stateLock) {
        val user = session?.userId?.takeIf { it.isNotBlank() } ?: throw SessionUnavailableException()
        Scope(user, epoch)
    }
    internal fun isCurrent(scope: Scope): Boolean = synchronized(stateLock) {
        epoch == scope.epoch && session?.userId == scope.userId
    }
    internal fun requireCurrent(scope: Scope) {
        if (!isCurrent(scope)) throw SessionUnavailableException()
    }
    /** Only memory publication is serialized with logout; never perform I/O under stateLock. */
    internal fun copyIfCurrent(scope: Scope, source: ByteArray, target: ByteArray, offset: Int, count: Int) = synchronized(stateLock) {
        requireCurrent(scope)
        if (count > 0) source.copyInto(target, offset, 0, count)
    }

    suspend fun login(email: String, password: String): Result<Unit> = withContext(Dispatchers.IO) {
        val generation = synchronized(stateLock) { advanceGeneration() }
        try {
            val dto = signIn(email, password).getOrThrow()
            val user = dto.user?.id ?: claims(dto.accessToken)?.get("sub")?.jsonPrimitive?.content
                ?: throw SessionUnavailableException()
            synchronized(stateLock) {
                if (epoch != generation) throw SessionUnavailableException()
                publish(StoredSession(dto.accessToken, dto.refreshToken, user, dto.user?.email ?: email.trim()))
            }
            Result.success(Unit)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { Result.failure(SessionUnavailableException()) }
    }

    /** Single flight: waiters re-read the rotated pair before considering another refresh. */
    suspend fun validSession(rejected: Lease? = null, scope: Scope? = null): Lease = withContext(Dispatchers.IO) {
        refreshLock.withLock {
            val (generation, current) = synchronized(stateLock) {
                if (scope != null) requireCurrent(scope)
                if (rejected != null && !isCurrent(rejected)) throw SessionUnavailableException()
                epoch to (session ?: throw SessionUnavailableException())
            }
            val mustRefresh = !unexpired(current.access) || rejected?.bearer == "Bearer ${current.access}"
            if (!mustRefresh) return@withLock Lease("Bearer ${current.access}", current.userId, generation)
            val refreshToken = current.refresh?.takeIf { it.isNotBlank() } ?: throw SessionUnavailableException()
            // Once rotation starts, finish persisting its result even if the requester is cancelled.
            withContext(NonCancellable) {
            val dto = try { withTimeout(30_000) { refresh(refreshToken).getOrThrow() } }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { throw SessionUnavailableException() } // Keep the persisted pair on network failure.
            val user = dto.user?.id ?: claims(dto.accessToken)?.get("sub")?.jsonPrimitive?.content ?: current.userId
            synchronized(stateLock) {
                if (epoch != generation || session != current || user != current.userId) throw SessionUnavailableException()
                if (!unexpired(dto.accessToken)) throw SessionUnavailableException()
                publish(StoredSession(dto.accessToken, dto.refreshToken ?: refreshToken, user, dto.user?.email ?: current.email))
                Lease("Bearer ${dto.accessToken}", user, generation)
            }
            }
        }
    }
    suspend fun refreshSession(): Result<Unit> = try {
        val old = synchronized(stateLock) { session?.let { Lease("Bearer ${it.access}", it.userId, epoch) } }
            ?: throw SessionUnavailableException()
        validSession(old)
        Result.success(Unit)
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { Result.failure(SessionUnavailableException()) }

    /** Old polling requests may refresh within one account, never switch to another account. */
    suspend fun authorize(supplied: String?): Lease {
        val (generation, before) = synchronized(stateLock) { epoch to (session ?: throw SessionUnavailableException()) }
        if (!supplied.isNullOrBlank()) {
            val subject = claims(supplied)?.get("sub")?.jsonPrimitive?.content
            if (subject != before.userId) throw SessionUnavailableException()
        }
        val lease = validSession()
        if (lease.userId != before.userId || lease.epoch != generation) throw SessionUnavailableException()
        return lease
    }
    companion object {
        @Volatile private var INSTANCE: AuthSessionManager? = null
        fun getInstance(context: Context): AuthSessionManager = INSTANCE ?: synchronized(this) {
            INSTANCE ?: AuthSessionManager(context.applicationContext).also { INSTANCE = it }
        }
    }
}
