package com.aura.music.data.media

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.room3.Room
import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.aura.music.core.EncryptedSessionStore
import com.aura.music.core.StoredSession
import com.aura.music.data.local.*
import com.aura.music.data.network.AuraApiService
import com.aura.music.data.network.KtorAuraApiService
import com.aura.music.data.repository.CloudFileRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.lang.reflect.Proxy
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.flow.collect

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34], application=Application::class, manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class FavoriteMediaConsistencyTest {
    @get:Rule val temp=TemporaryFolder()
    private lateinit var context: Context
    private lateinit var db: AuraDatabase
    private lateinit var dbFile: File
    private lateinit var downloads: File
    private val id="track:deezer:10001"
    private val coordinator=TrackMediaCoordinator.shared
    private fun open(): AuraDatabase = Room.databaseBuilder<AuraDatabase>(
        context=context, name=dbFile.absolutePath, factory={ AuraDatabaseConstructor.initialize() }
    ).setDriver(AndroidSQLiteDriver()).build()

    @Before fun setup() {
        val app=RuntimeEnvironment.getApplication()
        val files=File(temp.root,"files").apply { mkdirs() }
        context=object : ContextWrapper(app) { override fun getFilesDir(): File = files }
        dbFile=File(temp.root,"isolated-room.db")
        downloads=File(context.filesDir,"downloads").apply { mkdirs() }
        db=open()
    }
    @After fun close() { db.close() }
    private fun audio(): File = File(downloads,"track;deezer;10001.mp3").apply {
        writeBytes(ByteArray(4096).also { it[0]=73;it[1]=68;it[2]=51 })
    }
    private suspend fun seed() {
        db.trackDao().upsertTrack(TrackEntity(id=id,primaryArtistId=null,albumId=null,title="Synthetic",
            normalizedTitle="synthetic",displayArtistName="Synthetic",canonicalAudioSourceType="downloaded",
            isLiked=true,isDownloadedByAura=true,createdAt=1,updatedAt=1))
        db.trackLikeDao().insertLike(TrackLikeEntity(id,1))
    }
    private suspend fun link(uri: String, now: Long = 1): TrackMediaLinkEntity {
        val value=MediaLinkResolver.resolveOrCreateMediaLink(db,id,uri,4096,"audio/mpeg",now)
        db.trackDao().upsertTrackMediaLinks(listOf(value))
        return value
    }

    @Test fun completedDownloadBetweenSnapshotAndCleanupKeepsItsRealRoomLink() = runBlocking {
        seed()
        val observed=link(File(downloads,"missing.mp3").toURI().toString())
        val snapshotTaken=CompletableDeferred<Unit>(); val proceed=CompletableDeferred<Unit>()
        val scanner=LocalMediaScanner(db,MediaStoreAudioDataSource(context),context,{},
            mediaCoordinator=coordinator,readMediaStore={
                snapshotTaken.complete(Unit); proceed.await(); MediaScanResult(emptyList(),true)
            })
        val scan=async(Dispatchers.IO) { scanner.syncLocalMedia() }
        snapshotTaken.await()
        val content=ByteArray(4096).also { it[0]=73;it[1]=68;it[2]=51 }
        var transfers=0
        val client=HttpClient(MockEngine { transfers++; respond(content,HttpStatusCode.OK) }) { install(io.ktor.client.plugins.HttpTimeout) }
        try {
            val repo=CloudFileRepository(db,KtorAuraApiService(client,"https://api.example.invalid"),context,
                authTokenProvider={ "Bearer synthetic" })
            repo.downloadTrack(id,title="",artistName="").collect { it.getOrThrow() }
        } finally { client.close() }
        val file=File(downloads,"track;deezer;10001.mp3")
        assertEquals(1,transfers)
        proceed.complete(Unit); scan.await()
        val current=db.trackDao().getMediaLinkByTrackId(id)
        assertNotNull(current); assertNotEquals(observed.contentUri,current?.contentUri)
        assertTrue(file.exists()); assertTrue(db.trackLikeDao().isTrackLiked(id))
    }

    @Test fun repairBeforeLatePurgePreservesNewGenerationEvenAtSameTimestamp() = runBlocking {
        seed()
        val missing=File(downloads,"same.mp3").toURI().toString()
        val old=link(missing,100)
        val repaired=coordinator.withTrack(id) { link(missing,100) }
        assertTrue(repaired.lastScannedAt>old.lastScannedAt)
        assertFalse(coordinator.purgeObserved(db,downloads,old))
        assertEquals(repaired,db.trackDao().getMediaLinkByTrackId(id))
    }

    @Test fun incompleteMediaStoreScanCannotPurgeEvenAMissingPrivateFile() = runBlocking {
        seed()
        val observed=link(File(downloads,"missing.mp3").toURI().toString())
        val scanner=LocalMediaScanner(db,MediaStoreAudioDataSource(context),context,{},
            readMediaStore={ MediaScanResult(emptyList(),false) })
        scanner.syncLocalMedia()
        assertEquals(observed,db.trackDao().getMediaLinkByTrackId(id))
        assertTrue(db.trackLikeDao().isTrackLiked(id))
    }

    @Test fun validFallbackRepairsInsteadOfPurgingAndPreservesBytes() = runBlocking {
        seed(); val old=link(File(downloads,"obsolete.mp3").toURI().toString())
        val file=audio(); val bytes=file.readBytes()
        assertFalse(coordinator.purgeObserved(db,downloads,old))
        assertEquals(file.toURI().toString(),db.trackDao().getTrackContentUri(id))
        assertArrayEquals(bytes,file.readBytes()); assertTrue(db.trackLikeDao().isTrackLiked(id))
    }

    @Test fun favoriteWithoutUriRelinksWithoutAnyApiOrNetworkTransfer() = runBlocking {
        seed(); val file=audio()
        val prefs=context.getSharedPreferences("aura_prefs",Context.MODE_PRIVATE)
        prefs.edit().putBoolean("auto_download_favorites",true).commit()
        var apiCalls=0
        val api=Proxy.newProxyInstance(AuraApiService::class.java.classLoader,arrayOf(AuraApiService::class.java)) {
            _,method,_ -> apiCalls++; throw AssertionError("Unexpected network call: ${method.name}")
        } as AuraApiService
        val repo=CloudFileRepository(db,api,context)
        repo.autoDownloadFavoriteTrack(id)
        assertEquals(0,apiCalls); assertTrue(repo.networkTransfers.value.isEmpty())
        assertNotNull(db.trackDao().getTrackContentUri(id)); assertTrue(file.exists())
        val rows=db.trackDao().getLikedTracksFlow().first()
        assertFalse(rows.single().contentUri.isNullOrBlank())
        assertTrue(prefs.getBoolean("auto_download_favorites",false))
    }

    @Test fun databaseReopenPreservesFavoriteFileAndPreferenceWithoutSchemaChange() = runBlocking {
        seed(); val file=audio(); link(file.toURI().toString())
        val prefs=context.getSharedPreferences("aura_prefs",Context.MODE_PRIVATE)
        prefs.edit().putBoolean("auto_download_favorites",true).commit()
        db.close(); db=open()
        assertTrue(db.trackLikeDao().isTrackLiked(id)); assertNotNull(db.trackDao().getTrackContentUri(id))
        assertTrue(file.exists()); assertEquals(4096,file.length().toInt())
        assertTrue(prefs.getBoolean("auto_download_favorites",false))
    }

    @Test fun preferenceStoreReadsLegacyPairAndWritesOneRotatedRecord() {
        // Actual Android preferences, synthetic credentials; Keystore hardware is not emulated.
        val prefs=context.getSharedPreferences("test-auth-pair",Context.MODE_PRIVATE)
        prefs.edit().putString("auth_token","old").putString("refresh_token","old-refresh")
            .putString("user_id","A").putBoolean("unrelated",true).commit()
        val store=EncryptedSessionStore { prefs }
        assertEquals("old-refresh",store.read()?.refresh)
        val pair=StoredSession("new","new-refresh","A",null)
        store.write(pair)
        assertEquals(pair,EncryptedSessionStore { prefs }.read())
        assertFalse(prefs.contains("auth_token")); assertFalse(prefs.contains("refresh_token"))
        assertTrue(prefs.getBoolean("unrelated",false))
    }
}
