package com.aura.music.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class AndroidAuthenticatedApiTest {
    @Test fun getRetriesExactlyOnceWithRotatedHeader() = runBlocking {
        val store=MemorySessionStore(StoredSession(token(),"original","A",null))
        var refresh=0; val auth=manager(store) { refresh++; renewed() }
        val headers=mutableListOf<String?>()
        val client=HttpClient(MockEngine { request ->
            headers+=request.headers[HttpHeaders.Authorization]
            respond("", if(headers.size==1) HttpStatusCode.Unauthorized else HttpStatusCode.OK)
        })
        try {
            installSessionAuthorization(client,auth,Url("https://api.example.invalid"))
            val response=client.get("https://api.example.invalid/me/likes") { header(HttpHeaders.Authorization,auth.getBearerHeader()) }
            assertEquals(200,response.status.value); assertEquals(2,headers.size); assertEquals(1,refresh)
            assertEquals(auth.getBearerHeader(),headers.last())
        } finally { client.close() }
    }

    @Test fun second401DoesNotLoopAndPostDoesNotReplay() = runBlocking {
        for (method in listOf(HttpMethod.Get,HttpMethod.Post)) {
            val auth=manager(MemorySessionStore(StoredSession(token(),"original","A",null))) { renewed() }
            var requests=0
            val client=HttpClient(MockEngine { requests++; respond("",HttpStatusCode.Unauthorized) })
            try {
                installSessionAuthorization(client,auth,Url("https://api.example.invalid"))
                val response=client.request("https://api.example.invalid/downloads") {
                    this.method=method; header(HttpHeaders.Authorization,auth.getBearerHeader())
                }
                assertEquals(401,response.status.value)
                assertEquals(if(method==HttpMethod.Get) 2 else 1,requests)
            } finally { client.close() }
        }
    }

    @Test fun offlineAfter401KeepsSessionAndDoesNotReplay() = runBlocking {
        val original=StoredSession(token(),"original","A",null)
        val store=MemorySessionStore(original)
        val auth=manager(store) { Result.failure(IOException("offline")) }
        var calls=0
        val client=HttpClient(MockEngine { calls++; respond("",HttpStatusCode.Unauthorized) })
        try {
            installSessionAuthorization(client,auth,Url("https://api.example.invalid"))
            assertTrue(runCatching { client.get("https://api.example.invalid/me/likes") {
                header(HttpHeaders.Authorization,auth.getBearerHeader())
            } }.isFailure)
            assertEquals(1,calls); assertEquals(original,store.saved)
        } finally { client.close() }
    }

    @Test fun noAuthorizationIsSentToAnotherOrigin() = runBlocking {
        val auth=manager(MemorySessionStore(StoredSession(token(),"original","A",null))) { error("No refresh") }
        var calls=0
        val client=HttpClient(MockEngine { calls++; respond("") })
        try {
            installSessionAuthorization(client,auth,Url("https://api.example.invalid"))
            assertTrue(runCatching { client.get("https://other.example.invalid/me/likes") {
                header(HttpHeaders.Authorization,auth.getBearerHeader())
            } }.isFailure)
            assertEquals(0,calls)
        } finally { client.close() }
    }

    @Test fun responseArrivingAfterLogoutIsDiscarded() = runBlocking {
        val auth=manager(MemorySessionStore(StoredSession(token(),"original","A",null))) { error("No refresh") }
        var calls=0
        val client=HttpClient(MockEngine {
            calls++; auth.logout(); respond("private response",HttpStatusCode.OK)
        })
        try {
            installSessionAuthorization(client,auth,Url("https://api.example.invalid:443"))
            assertTrue(runCatching { client.get("https://api.example.invalid/me/likes") {
                header(HttpHeaders.Authorization,auth.getBearerHeader())
            } }.isFailure)
            assertEquals(1,calls); assertFalse(auth.isLoggedIn.value)
        } finally { client.close() }
    }
}
