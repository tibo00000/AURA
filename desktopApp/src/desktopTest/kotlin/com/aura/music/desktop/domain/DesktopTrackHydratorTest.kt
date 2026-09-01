package com.aura.music.desktop.domain

import com.aura.music.desktop.utils.DesktopTrackMatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DesktopTrackHydratorTest {

    @Test
    fun testExtractDeezerIdFromTrkToken() {
        // Encodage base64 url-safe de v1:track:deezer:3135556 -> djE6dHJhY2s6ZGVlemVyOjMxMzU1NTY
        val trackId = trk_djE6dHJhY2s6ZGVlemVyOjMxMzU1NTY
        val deezerId = DesktopTrackMatcher.extractDeezerId(trackId)
        assertNotNull(deezerId)
        assertEquals(3135556, deezerId)
    }

    @Test
    fun testExtractDeezerIdDirect() {
        val trackId = deezer:12345678
        val deezerId = DesktopTrackMatcher.extractDeezerId(trackId)
        assertEquals(12345678, deezerId)
    }

    @Test
    fun testExtractDeezerIdNumeric() {
        val trackId = 98765432
        val deezerId = DesktopTrackMatcher.extractDeezerId(trackId)
        assertEquals(98765432, deezerId)
    }
}
