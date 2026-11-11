package com.aura.music.core

import com.aura.music.data.network.SupabaseSessionDto
import com.aura.music.data.network.SupabaseUserDto
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

internal class MemorySessionStore(var saved: StoredSession?) : SessionStore {
    override fun read() = saved
    override fun write(session: StoredSession?) { saved = session }
}
internal fun token(user: String = "A", expires: Long = 10_000, nonce: Int = 0): String {
    val encoder = Base64.getUrlEncoder().withoutPadding()
    return encoder.encodeToString("{}".toByteArray()) + "." +
        encoder.encodeToString("{\"sub\":\"$user\",\"exp\":$expires,\"nonce\":$nonce}".toByteArray()) + ".synthetic"
}
internal fun manager(store: MemorySessionStore, refresh: suspend (String) -> Result<SupabaseSessionDto>) =
    AuthSessionManager(store, { _, _ -> error("No real login") }, refresh, { 1000 })
internal fun renewed(user: String = "A") = Result.success(SupabaseSessionDto(token(user, nonce=1), refreshToken = "rotated", user = SupabaseUserDto(user)))

class AuthSessionRenewalTest {
    @Test fun expiredTokenAfterRestartRefreshesAndPersistsPair() = runBlocking {
        val store = MemorySessionStore(StoredSession(token(expires=1), "original", "A", null))
        var calls = 0
        val auth = manager(store) { calls++; assertEquals("original", it); renewed() }
        assertEquals("Bearer ${token(nonce=1)}", auth.validSession().bearer)
        assertEquals("rotated", store.saved?.refresh)
        val restarted = manager(store) { error("Unexpected second refresh") }
        assertEquals(auth.getBearerHeader(), restarted.validSession().bearer)
        assertEquals(1, calls)
    }

    @Test fun concurrentExpiredRequestsUseOneRotation() = runBlocking {
        val store = MemorySessionStore(StoredSession(token(expires=1), "original", "A", null))
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val count = AtomicInteger()
        val auth = manager(store) { count.incrementAndGet(); entered.complete(Unit); release.await(); renewed() }
        val calls = List(12) { async(Dispatchers.Default) { auth.validSession() } }
        entered.await(); release.complete(Unit)
        calls.awaitAll().forEach { assertEquals("Bearer ${token(nonce=1)}", it.bearer) }
        assertEquals(1, count.get())
    }

    @Test fun logoutRejectsLateRefresh() = runBlocking {
        val store = MemorySessionStore(StoredSession(token(expires=1), "original", "A", null))
        val entered=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
        val auth=manager(store) { entered.complete(Unit); release.await(); renewed() }
        val pending=async { runCatching { auth.validSession() } }
        entered.await(); auth.logout(); release.complete(Unit)
        assertTrue(pending.await().isFailure); assertNull(store.saved); assertFalse(auth.isLoggedIn.value)
    }

    @Test fun newAccountRejectsLateRefreshOfOldAccount() = runBlocking {
        val store=MemorySessionStore(StoredSession(token(expires=1),"original","A",null))
        val entered=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
        val auth=manager(store) { entered.complete(Unit); release.await(); renewed() }
        val pending=async { runCatching { auth.validSession() } }
        entered.await(); auth.saveSession(token("B"),"B-refresh","B",null); release.complete(Unit)
        assertTrue(pending.await().isFailure); assertEquals("B",store.saved?.userId)
        assertEquals("B-refresh",store.saved?.refresh)
    }

    @Test fun offlineRefreshKeepsAllCredentials() = runBlocking {
        val original=StoredSession(token(expires=1),"original","A",null)
        val store=MemorySessionStore(original)
        val auth=manager(store) { Result.failure(IOException("synthetic offline")) }
        assertTrue(runCatching { auth.validSession() }.isFailure)
        assertEquals(original,store.saved); assertTrue(auth.isLoggedIn.value)
    }

    @Test fun cancelledRequesterDoesNotAbandonRotatedPair() = runBlocking {
        val store=MemorySessionStore(StoredSession(token(expires=1),"original","A",null))
        val entered=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
        val auth=manager(store) { entered.complete(Unit); release.await(); renewed() }
        val pending=launch { auth.validSession() }
        entered.await(); pending.cancel(); release.complete(Unit); pending.join()
        assertEquals("rotated",store.saved?.refresh)
        assertEquals("Bearer ${token(nonce=1)}",auth.validSession().bearer)
    }

    @Test fun oldPollingTokenCannotBecomeNewAccountRequest() = runBlocking {
        val store=MemorySessionStore(StoredSession(token("B"),"B-refresh","B",null))
        val auth=manager(store) { error("No refresh") }
        assertTrue(runCatching { auth.authorize("Bearer ${token("A")}") }.isFailure)
    }
}
