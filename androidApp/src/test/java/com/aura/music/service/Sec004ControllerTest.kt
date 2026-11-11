package com.aura.music.service

import android.app.Application
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.aura.music.core.PrivateStreamPolicy
import com.aura.music.data.local.TrackListRow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.net.URI
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class,manifest=Config.NONE)
class Sec004ControllerTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun controller(pkg: String = "attacker.app", uid: Int = 43210, trusted: Boolean = false) =
        MediaSession.ControllerInfo.createTestOnlyControllerInfo(pkg, 42, uid, 1, 1, trusted, Bundle())
    private fun internal() = controller(context.packageName, context.applicationInfo.uid)
    private fun row(id: String = "track:deezer:1", uri: String? = null) = TrackListRow(
        id=id, artistId=null, albumId=null, title="Synthetic", artistName="Synthetic", albumTitle=null,
        contentUri=uri, durationMs=1000, coverUri=null, isLiked=true)
    private fun resolver(rows: List<TrackListRow>, lookedUp: MutableList<String> = mutableListOf()) =
        ControllerMediaResolver(context, ControllerTrustPolicy(context), PrivateStreamPolicy(URI("https://api.example.invalid")),
            { id -> lookedUp.add(id); rows.find { it.id == id } }, { rows })

    @Test fun untrustedAndPackageOnlyClaimsAreRejected() {
        val trust = ControllerTrustPolicy(context)
        assertFalse(trust.allows(controller()))
        assertFalse(trust.allows(controller(context.packageName))) // AURA name with another UID.
        assertFalse(trust.allows(controller("com.google.android.projection.gearhead")))
        assertFalse(trust.allows(controller("com.google.android.googlequicksearchbox")))
        assertTrue(trust.isInternal(internal())); assertTrue(trust.allows(internal()))
        assertTrue(trust.allows(controller("android", 1000, true)))
    }

    @Test fun streamPolicyRejectsOriginAndPathLookalikes() {
        val policy=PrivateStreamPolicy(URI("https://api.example.invalid"))
        assertTrue(policy.allows(URI("https://API.EXAMPLE.INVALID:443/me/sync/files/track%3Adeezer%3A1")))
        assertEquals("track:deezer:1",policy.trackId(URI("https://api.example.invalid/me/sync/files/track%3Adeezer%3A1")))
        val invalid=listOf("http://api.example.invalid/me/sync/files/1", "https://api.example.invalid:444/me/sync/files/1",
            "https://api.example.invalid.attacker.invalid/me/sync/files/1", "https://evilapi.example.invalid/me/sync/files/1",
            "https://user@api.example.invalid/me/sync/files/1", "https://api.example.invalid/me/sync/files/",
            "https://api.example.invalid/me/sync/filesx/1", "https://api.example.invalid/me/sync/files/%2e%2e",
            "https://api.example.invalid/me/sync/files/%5cfile", "https://api.example.invalid/me/sync/files/1/extra")
        invalid.forEach { assertFalse(it,policy.allows(URI(it))) }
    }

    @Test fun actualServiceCallbackRejectsUntrustedAndPreservesTrustedTransportCommands() {
        val service = Robolectric.buildService(PlaybackService::class.java).get()
        val callback = service.librarySessionCallback
        val player = ExoPlayer.Builder(context).build()
        val session = MediaLibraryService.MediaLibrarySession.Builder(context, player, callback).build()
        try {
            assertFalse(callback.onConnect(session, controller()).isAccepted)
            val system = callback.onConnect(session, controller("android", 1000, true))
            assertTrue(system.isAccepted)
            assertTrue(system.availablePlayerCommands.contains(Player.COMMAND_PLAY_PAUSE))
            assertTrue(system.availablePlayerCommands.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
            val auto = callback.onConnect(session, controller("com.google.android.projection.gearhead", 12345, true))
            assertTrue(auto.isAccepted)
            assertTrue(auto.availablePlayerCommands.contains(Player.COMMAND_SET_MEDIA_ITEM))
            assertTrue(auto.availablePlayerCommands.contains(Player.COMMAND_CHANGE_MEDIA_ITEMS))
            assertTrue(callback.onConnect(session, internal()).isAccepted)
            assertEquals(androidx.media3.session.SessionError.ERROR_PERMISSION_DENIED,
                callback.onGetLibraryRoot(session, controller(), null).get().resultCode)
            assertEquals(androidx.media3.session.SessionError.ERROR_PERMISSION_DENIED,
                callback.onGetChildren(session, controller(), "tracks", 0, 10, null).get().resultCode)
        } finally { session.release(); player.release() }
    }

    @Test fun actualServiceCallbacksCompleteWithErrorWhenSessionInitializationFails() = runBlocking {
        val service = Robolectric.buildService(PlaybackService::class.java).get()
        val field = PlaybackService::class.java.getDeclaredField("privatePlaybackReady").apply { isAccessible=true }
        val readiness = field.get(service) as PlaybackSessionReadiness
        assertFalse(readiness.initialize { throw IllegalStateException("synthetic initialization failure") })
        val callback=service.librarySessionCallback
        val player=ExoPlayer.Builder(context).build()
        val session=MediaLibraryService.MediaLibrarySession.Builder(context,player,callback).build()
        try {
            val item=MediaItem.Builder().setMediaId("synthetic").build()
            val add=callback.onAddMediaItems(session,internal(),listOf(item))
            val set=callback.onSetMediaItems(session,internal(),listOf(item),0,0)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            for (future in listOf(add,set)) {
                assertTrue(future.isDone)
                val error=runCatching { future.get(1,java.util.concurrent.TimeUnit.SECONDS) }.exceptionOrNull()
                assertTrue(error is java.util.concurrent.ExecutionException)
                assertTrue(error?.cause is IllegalStateException)
            }
            assertEquals(0,player.mediaItemCount)
        } finally { session.release();player.release() }
    }

    @Test fun untrustedCannotEvenReadCatalogueOrResolveAUri() = runBlocking {
        val lookups=mutableListOf<String>()
        val items=listOf(MediaItem.Builder().setMediaId("track:deezer:1").setUri("https://attacker.invalid/a").build())
        assertTrue(runCatching { resolver(listOf(row()),lookups).resolve(controller(),items) }.isFailure)
        assertTrue(lookups.isEmpty())
    }

    @Test fun trustedSystemCannotSupplyUnknownItemsOrAnyArbitraryUri() = runBlocking {
        val trusted=controller("android",1000,true)
        for (uri in listOf("https://attacker.invalid/a", "http://attacker.invalid/a", "file:///private.mp3", "content://attacker/audio")) {
            val unknown=MediaItem.Builder().setMediaId("unknown").setUri(uri).build()
            assertTrue(runCatching { resolver(emptyList()).resolve(trusted,listOf(unknown)) }.isFailure)
        }
        val malformed=MediaItem.Builder().setMediaId("../track").build()
        assertTrue(runCatching { resolver(emptyList()).resolve(trusted,listOf(malformed)) }.isFailure)
    }

    @Test fun multiItemBundleRoundTripCannotPassAnUnknownUriOrChangeKnownSources() = runBlocking {
        val trusted=controller("android",1000,true)
        val first=MediaItem.Builder().setMediaId("track:deezer:1").setUri("https://attacker.invalid/first").build()
        val second=MediaItem.Builder().setMediaId("unknown").setUri("https://attacker.invalid/second").build()
        val remote=listOf(first,second).map { MediaItem.fromBundle(it.toBundleIncludeLocalConfiguration()) }
        assertNotNull(remote.last().localConfiguration) // Media3 1.4.1 actually transports caller URIs.
        assertTrue(runCatching { resolver(listOf(row())).resolve(trusted,remote) }.isFailure)
        val known=resolver(listOf(row(),row("track:deezer:2"))).resolve(trusted,
            listOf(first,second.buildUpon().setMediaId("track:deezer:2").build()))
        assertEquals(2,known.size)
        assertTrue(known.all { it.localConfiguration!!.uri.host=="api.example.invalid" })
        assertTrue(known.all { it.requestMetadata.mediaUri==null })
    }

    @Test fun internalOnlineTracksAndKnownOfflineTracksStillResolve() = runBlocking {
        val input=MediaItem.Builder().setMediaId("trk_online").setUri("https://api.example.invalid/me/sync/files/trk_online").build()
        assertEquals("trk_online",resolver(emptyList()).resolve(internal(),listOf(input)).single().mediaId)
        val rows=listOf(row("track:local:1","content://media/external/audio/media/1"),
            row("track:local:2","file:///synthetic/downloads/2.mp3"),row("track:local:3","/synthetic/downloads/3.mp3"))
        val items=rows.map { MediaItem.Builder().setMediaId(it.id).setUri("https://attacker.invalid/ignored").build() }
        val resolved=resolver(rows).resolve(controller("android",1000,true),items)
        assertEquals(rows.map { it.contentUri },resolved.map { it.localConfiguration?.uri.toString() })
    }

    @Test fun legacyRequestMetadataDoesNotAuthorizeAnExternalSource() = runBlocking {
        val item=MediaItem.Builder().setRequestMetadata(MediaItem.RequestMetadata.Builder()
            .setMediaUri(android.net.Uri.parse("https://attacker.invalid/legacy")).build()).build()
        assertTrue(runCatching { resolver(emptyList()).resolve(controller("android",1000,true),listOf(item)) }.isFailure)
        val search=item.buildUpon().setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery("Synthetic").build()).build()
        val result=resolver(listOf(row())).resolve(controller("android",1000,true),listOf(search)).single()
        assertEquals("track:deezer:1",result.mediaId); assertEquals("api.example.invalid",result.localConfiguration!!.uri.host)
    }
}
