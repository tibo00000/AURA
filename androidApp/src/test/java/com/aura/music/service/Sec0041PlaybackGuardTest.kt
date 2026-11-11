package com.aura.music.service

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.aura.music.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34], application=Application::class, manifest=Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class Sec0041PlaybackGuardTest {
    @get:Rule val temp=TemporaryFolder()
    private val context get()=RuntimeEnvironment.getApplication()
    private fun wav(): ByteArray {
        val size=8000*2*5
        return ByteBuffer.allocate(44+size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray());putInt(36+size);put("WAVEfmt ".toByteArray());putInt(16)
            putShort(1);putShort(1);putInt(8000);putInt(16000);putShort(2);putShort(16)
            put("data".toByteArray());putInt(size)
        }.array()
    }
    private fun awaitPlayer(player: ExoPlayer, condition: () -> Boolean) {
        val deadline=System.nanoTime()+15_000_000_000
        while (!condition() && System.nanoTime()<deadline && player.playerError==null) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10));Thread.yield()
        }
        assertNull(player.playerError);assertTrue("Player state=${player.playbackState}, buffered=${player.bufferedPosition}",condition())
    }
    @Test fun realTlsAudioIsBufferedThenDiscardedAfterAccountChangeButNotRefresh() = runBlocking {
        LoopbackAudioFixture().use { tls ->
            val origin=tls.endpoint { LoopbackAudioFixture.Reply(200,wav()) }
            val auth=manager(MemorySessionStore(StoredSession(token(),"synthetic","A",null))) { renewed() }
            val database=StandaloneDatabaseProvider(context)
            val cache=SimpleCache(temp.newFolder(),LeastRecentlyUsedCacheEvictor(300L*1024*1024),database)
            val sources=MediaCacheManager.dataSourceFactory(context,cache,auth,URI(origin.base),
                NoRedirectAudioDataSource.Factory(URI(origin.base),tls.client))
            val player=ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(sources))
                .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(100,1000,10,10).build()).build()
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
            val guard=PrivatePlaybackGuard(player,auth.sessionScope.value)
            scope.launch(start=CoroutineStart.UNDISPATCHED) { auth.sessionScope.collect(guard::onScopeChanged) }
            try {
                player.setMediaItem(MediaItem.Builder().setMediaId("private").setUri(origin.base+"/me/sync/files/private").build())
                player.prepare()
                awaitPlayer(player) { player.playbackState==Player.STATE_READY && player.bufferedPosition>0 }
                assertTrue(origin.received.isNotEmpty())
                assertTrue(auth.refreshSession().isSuccess)
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(Player.STATE_READY,player.playbackState);assertTrue(player.bufferedPosition>0)
                auth.logout();auth.saveSession(token("B"),"synthetic-B","B",null)
                awaitPlayer(player) { player.playbackState==Player.STATE_IDLE && player.mediaItemCount==0 }
                assertEquals(0,player.bufferedPosition);assertFalse(player.playWhenReady)
            } finally { scope.cancel();player.release();cache.release();database.close() }
        }
    }
    @Test fun localFavoriteContinuesAndOnlyQueuedPrivateItemsAreRemoved() {
        val player=ExoPlayer.Builder(context).build()
        val local=File(temp.root,"favorite.wav").apply { writeBytes(wav()) }
        val auth=manager(MemorySessionStore(StoredSession(token(),"synthetic","A",null))) { renewed() }
        val guard=PrivatePlaybackGuard(player,auth.sessionScope.value)
        try {
            player.setMediaItems(listOf(MediaItem.fromUri(Uri.fromFile(local)),
                MediaItem.fromUri("https://api.example.invalid/me/sync/files/private")))
            auth.logout();guard.onScopeChanged(auth.sessionScope.value)
            assertEquals(1,player.mediaItemCount)
            assertEquals(Uri.fromFile(local),player.currentMediaItem?.localConfiguration?.uri)
            assertArrayEquals(wav(),local.readBytes())
        } finally { player.release() }
    }
}
