package com.aura.music.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaLinkResolverTest {

    @Test
    fun testSyntheticMediaStoreIdIsStrictlyNegative() {
        val testIds = listOf(
            "track:cloud:123",
            "trk_cloud_sdm_cartiersantos",
            "track:deezer:999999",
            "local:audio:1",
            "",
            "a_very_long_track_id_with_special_characters_!@#\$%^&*()_+"
        )

        for (id in testIds) {
            val syntheticId = MediaLinkResolver.syntheticMediaStoreId(id)
            assertTrue("Expected negative synthetic ID for track '$id' but got $syntheticId", syntheticId < 0L)
            assertTrue("Expected ID to not exceed -1L", syntheticId <= -1L)
        }
    }

    @Test
    fun testSyntheticMediaStoreIdIsDeterministic() {
        val trackId = "trk_cloud_sdm_cartiersantos"
        val id1 = MediaLinkResolver.syntheticMediaStoreId(trackId)
        val id2 = MediaLinkResolver.syntheticMediaStoreId(trackId)
        assertEquals(id1, id2)
    }
}
