package com.aura.music.domain.search

import com.aura.music.data.local.AlbumBrowseRow
import com.aura.music.data.local.ArtistBrowseRow
import com.aura.music.data.local.TrackListRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSearchEngineTest {

    @Test
    fun testSearchNormalizerLeetspeakAndAccents() {
        // Accents & Diacritics
        assertEquals("motorhead", SearchNormalizer.normalize("Motörhead"))
        assertEquals("mo", SearchNormalizer.normalize("MØ"))
        assertEquals("beyonce", SearchNormalizer.normalize("Beyoncé"))
        assertEquals("celine dion", SearchNormalizer.normalize("Céline Dion"))

        // Musical Leetspeak
        assertEquals("kesha", SearchNormalizer.normalize("Ke\$ha"))
        assertEquals("asap rocky", SearchNormalizer.normalize("A\$AP Rocky"))
        assertEquals("pink", SearchNormalizer.normalize("P!nk"))
        assertEquals("terence", SearchNormalizer.normalize("T3R3NC3"))
        assertEquals("black", SearchNormalizer.normalize("6LACK"))

        // Features & Noise Stripping
        assertEquals("love the way you lie", SearchNormalizer.normalize("Love the Way You Lie (feat. Rihanna)"))
        assertEquals("blinding lights", SearchNormalizer.normalize("Blinding Lights [Official Audio]"))
    }

    @Test
    fun testPureNumericGuard() {
        // Guard: Les tokens purement numériques ne doivent pas être corrompus par le leetspeak
        assertEquals("2024", SearchNormalizer.normalize("2024"))
        assertEquals("1999", SearchNormalizer.normalize("1999"))
        assertEquals("24", SearchNormalizer.normalize("24"))
        assertEquals("blink 182", SearchNormalizer.normalize("Blink-182"))
    }

    @Test
    fun testAcronymsAndAmpersand() {
        assertEquals("rnb", SearchNormalizer.normalize("R&B"))
        val tokensRnB = SearchNormalizer.tokenize("R&B")
        assertEquals(listOf("rnb"), tokensRnB)

        assertEquals("simon and garfunkel", SearchNormalizer.normalize("Simon & Garfunkel"))
        val tokensSG = SearchNormalizer.tokenize("Simon & Garfunkel")
        assertEquals(listOf("simon", "and", "garfunkel"), tokensSG)
    }

    @Test
    fun testFuzzyMatcher() {
        // Adaptatif : len < 4 = distance 0, len 4..6 = distance 1, len >= 7 = distance 2
        assertTrue(FuzzyMatcher.isFuzzyMatch("lipa", "lpipa")) // transposition 1
        assertTrue(FuzzyMatcher.isFuzzyMatch("sheran", "sheeran")) // insertion 1
        assertTrue(FuzzyMatcher.isFuzzyMatch("weekend", "weeknd")) // suppression 1

        assertFalse(FuzzyMatcher.isFuzzyMatch("dua", "dua_x")) // trop court pour tolérance floue (len < 4)
    }

    @Test
    fun testMultiTokenSearchAndFieldMatching() {
        val tracks = listOf(
            createSampleTrack("1", "Love the Way You Lie", "Eminem feat. Rihanna", "Recovery"),
            createSampleTrack("2", "Diamonds", "Rihanna", "Unapologetic"),
            createSampleTrack("3", "Lose Yourself", "Eminem", "8 Mile"),
            createSampleTrack("4", "Levitating", "Dua Lipa", "Future Nostalgia")
        )
        val index = LocalSearchIndex.build(tracks, emptyList(), emptyList())

        // 1. Recherche multi-mots non contigus dans le titre ("love lie")
        val resultsLoveLie = LocalSearchEngine.searchTracks(index, "love lie")
        assertEquals(1, resultsLoveLie.size)
        assertEquals("1", resultsLoveLie.first().id)

        // 2. Recherche multi-champs Artiste + Feat ("eminem rihanna")
        val resultsEminemRihanna = LocalSearchEngine.searchTracks(index, "eminem rihanna")
        assertEquals(1, resultsEminemRihanna.size)
        assertEquals("1", resultsEminemRihanna.first().id)

        // 3. Recherche avec faute de frappe ("dua lpipa")
        val resultsFuzzy = LocalSearchEngine.searchTracks(index, "dua lpipa")
        assertEquals(1, resultsFuzzy.size)
        assertEquals("4", resultsFuzzy.first().id)
    }

    @Test
    fun testDynamicAffinityBoostWithoutRebuild() {
        val track1 = createSampleTrack("1", "Song A", "Artist X", "Album")
        val track2 = createSampleTrack("2", "Song B", "Artist X", "Album")
        val index = LocalSearchIndex.build(listOf(track1, track2), emptyList(), emptyList())

        // Sans like, l'ordre est alphabétique/naturel
        val resultsInitial = LocalSearchEngine.searchTracks(index, "Artist X")
        assertEquals(2, resultsInitial.size)

        // Quand track2 est likée dynamiquement, elle remonte grâce au boost +30 sans reconstruire l'index
        val resultsWithLike = LocalSearchEngine.searchTracks(
            index = index,
            query = "Artist X",
            likedTrackIds = setOf("2")
        )
        assertEquals(2, resultsWithLike.size)
        assertEquals("2", resultsWithLike.first().id)
        assertTrue(resultsWithLike.first().isLiked)
    }

    @Test
    fun testScalability50kTracks() {
        val count = 50_000
        val sampleTracks = ArrayList<TrackListRow>(count)
        val artists = listOf("The Weeknd", "Dua Lipa", "Eminem", "Taylor Swift", "Drake", "Ed Sheeran", "Queen", "Ke\$ha")

        for (i in 0 until count) {
            val artist = artists[i % artists.size]
            sampleTracks.add(
                createSampleTrack(
                    id = "trk_$i",
                    title = "Track Title $i Special Edition",
                    artistName = artist,
                    albumTitle = "Album Number ${i / 10}"
                )
            )
        }

        // Mesure du temps de construction de l'index sur 50 000 éléments
        val startBuild = System.currentTimeMillis()
        val index = LocalSearchIndex.build(sampleTracks, emptyList(), emptyList())
        val buildDuration = System.currentTimeMillis() - startBuild
        println("Temps de construction index 50k: ${buildDuration}ms")
        assertTrue("Indexation de 50k morceaux trop lente: ${buildDuration}ms", buildDuration < 600)

        // Mesure de recherche sur préfixe fréquent (2 lettres)
        val startQueryShort = System.currentTimeMillis()
        val resShort = LocalSearchEngine.searchTracks(index, "th", limit = 20)
        val shortDuration = System.currentTimeMillis() - startQueryShort
        println("Temps recherche préfixe court 'th' sur 50k: ${shortDuration}ms")
        assertTrue("Recherche courte trop lente: ${shortDuration}ms", shortDuration < 20)
        assertTrue(resShort.isNotEmpty())

        // Mesure de recherche multi-tokens ("weeknd special")
        val startQueryMulti = System.currentTimeMillis()
        val resMulti = LocalSearchEngine.searchTracks(index, "weeknd special", limit = 20)
        val multiDuration = System.currentTimeMillis() - startQueryMulti
        println("Temps recherche multi-tokens sur 50k: ${multiDuration}ms")
        assertTrue("Recherche multi-tokens trop lente: ${multiDuration}ms", multiDuration < 15)
        assertTrue(resMulti.isNotEmpty())
    }

    private fun createSampleTrack(
        id: String,
        title: String,
        artistName: String,
        albumTitle: String
    ): TrackListRow = TrackListRow(
        id = id,
        artistId = "art_$id",
        albumId = "alb_$id",
        title = title,
        artistName = artistName,
        albumTitle = albumTitle,
        contentUri = "content://media/$id",
        durationMs = 180000L,
        coverUri = null,
        isLiked = false,
        createdAt = 1000L,
        updatedAt = 1000L
    )
}
