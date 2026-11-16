package com.aura.music.data.repository

import com.aura.music.data.local.PlaylistSyncStateEntity
import com.aura.music.data.network.PlaylistRefreshResult
import com.aura.music.data.playlist.acknowledgeRefresh
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PlaylistRefreshAcknowledgementTest {
    private val older = PlaylistRefreshResult("first", 2, "2026-11-14T19:00:00.000001Z", 1, 0, 0)
    private val newer = older.copy(requestId = "second", completedAt = "2026-11-14T19:00:00.000002Z", addedCount = 2)
    private val synced = PlaylistSyncStateEntity(
        playlistId = "playlist", userId = "account", serverUpdatedAt = newer.completedAt,
        orderToken = "order", appliedRevision = 40, sourceRevision = 2,
        lastRefreshJson = Json.encodeToString(newer), pendingRefreshJson = "request-first",
    )

    @Test
    fun replayOfLostAcknowledgementPreservesLaterRefreshFromAnotherDevice() {
        val applied = synced.acknowledgeRefresh("request-first", older)
        assertNull(applied.pendingRefreshJson)
        assertEquals(synced.copy(pendingRefreshJson = null), applied)
    }

    @Test
    fun newerAcknowledgementUpdatesTheReportAndClearsItsRequest() {
        val withOlderReport = synced.copy(lastRefreshJson = Json.encodeToString(older), pendingRefreshJson = "request-second")
        val applied = withOlderReport.acknowledgeRefresh("request-second", newer)
        assertEquals(Json.encodeToString(newer), applied.lastRefreshJson)
        assertNull(applied.pendingRefreshJson)
    }

    @Test
    fun earlierResponseCannotClearAnotherRequestOrOverwriteAnotherSource() {
        assertSame(synced, synced.acknowledgeRefresh("other-request", older))
        val newSource = synced.copy(sourceRevision = 3)
        assertSame(newSource, newSource.acknowledgeRefresh("request-first", older))
    }
}
