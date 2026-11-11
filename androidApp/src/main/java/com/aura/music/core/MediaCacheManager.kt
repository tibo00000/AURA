package com.aura.music.core

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.File
import java.net.URI
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.Cache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
object MediaCacheManager {
    private var simpleCache: SimpleCache? = null

    @Synchronized
    fun getCache(context: Context): SimpleCache {
        if (simpleCache == null) {
            val cacheDir = File(context.applicationContext.cacheDir, "aura_media_streaming_cache")
            val evictor = LeastRecentlyUsedCacheEvictor(300L * 1024 * 1024) // 300 MB
            val databaseProvider = StandaloneDatabaseProvider(context.applicationContext)
            simpleCache = SimpleCache(cacheDir, evictor, databaseProvider)
        }
        return simpleCache!!
    }

    suspend fun evictTrack(context: Context, trackId: String) = withContext(Dispatchers.IO) {
        PrivateStreamCache.evict(getCache(context), URI(com.aura.music.data.network.BuildConfig.API_BASE_URL),
            trackId, PrivateCacheGenerationStore(context.applicationContext))
    }

    internal fun dataSourceFactory(context: Context, cache: Cache, auth: AuthSessionManager,
        origin: URI, transport: DataSource.Factory = NoRedirectAudioDataSource.Factory(origin)): DataSource.Factory {
        val local = DefaultDataSource.Factory(context, transport)
        return DataSource.Factory {
            PrivateCacheDataSource(auth, origin, cache, local, PrivateCacheGenerationStore(context.applicationContext)) { captured ->
                SessionHttpDataSource(transport, auth, origin, captured)
            }
        }
    }

    fun createMediaSourceFactory(context: Context): DefaultMediaSourceFactory {
        val appContext = context.applicationContext
        val streamOrigin = java.net.URI(com.aura.music.data.network.BuildConfig.API_BASE_URL)
        // Share the credential-free transport/pool; session headers remain per opening.
        val baseFactory = NoRedirectAudioDataSource.Factory(streamOrigin)
        // Media3 creates its upstream on the playback/loader thread, not during service onCreate.
        val cacheDataSourceFactory = DataSource.Factory {
            dataSourceFactory(appContext, getCache(appContext),
                AuthSessionManager.getInstance(appContext), streamOrigin, baseFactory).createDataSource()
        }

        return DefaultMediaSourceFactory(appContext)
            .setDataSourceFactory(cacheDataSourceFactory)
            .setLoadErrorHandlingPolicy(object : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy() {
                override fun getRetryDelayMsFor(loadErrorInfo: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo): Long {
                    var currentCause: Throwable? = loadErrorInfo.exception
                    while (currentCause != null) {
                        if (currentCause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                            if (currentCause.responseCode in listOf(400, 401, 403, 404)) {
                                return androidx.media3.common.C.TIME_UNSET
                            }
                        }
                        currentCause = currentCause.cause
                    }
                    return super.getRetryDelayMsFor(loadErrorInfo)
                }

                override fun getMinimumLoadableRetryCount(dataType: Int): Int = 1
            })
    }
}
