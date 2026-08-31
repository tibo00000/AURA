package com.aura.music.domain.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.min

class FuzzyMatcherTest {

    @Test
    fun testAdjacentTransposition() {
        // Transposition de 2 caractères adjacents = distance 1
        val dist1 = FuzzyMatcher.calculateDamerauLevenshtein("lpipa", "lipa", maxDistance = 2)
        assertEquals(1, dist1)

        val dist2 = FuzzyMatcher.calculateDamerauLevenshtein("sheran", "sheeran", maxDistance = 2)
        assertEquals(1, dist2)

        val dist3 = FuzzyMatcher.calculateDamerauLevenshtein("tayolr", "taylor", maxDistance = 2)
        assertEquals(1, dist3)

        // Exact match
        assertEquals(0, FuzzyMatcher.calculateDamerauLevenshtein("eminem", "eminem", maxDistance = 2))
    }

    @Test
    fun testIsFuzzyMatch() {
        // Longueur 4..6 : tolérance 1
        assertTrue(FuzzyMatcher.isFuzzyMatch("lipa", "lpipa"))
        assertTrue(FuzzyMatcher.isFuzzyMatch("lpipa", "lipa"))
        assertTrue(FuzzyMatcher.isFuzzyMatch("weeknd", "weekend"))
        assertTrue(FuzzyMatcher.isFuzzyMatch("sheeran", "sheran"))

        // Longueur < 4 : pas de tolérance floue (distance 0)
        assertFalse(FuzzyMatcher.isFuzzyMatch("dua", "daux"))
        assertTrue(FuzzyMatcher.isFuzzyMatch("dua", "dua"))

        // Préfixes
        assertTrue(FuzzyMatcher.isFuzzyMatch("dua", "dua lipa"))
    }

    @Test
    fun testGoldenMatrixComparisonAgainst1000WordPairs() {
        // Reference 2D matrix algorithm for golden comparison
        fun referenceDamerauLevenshtein(s1: String, s2: String, maxDistance: Int): Int {
            val len1 = s1.length
            val len2 = s2.length
            if (kotlin.math.abs(len1 - len2) > maxDistance) return maxDistance + 1
            if (len1 == 0) return len2
            if (len2 == 0) return len1

            val dp = Array(len1 + 1) { IntArray(len2 + 1) }
            for (i in 0..len1) dp[i][0] = i
            for (j in 0..len2) dp[0][j] = j

            for (i in 1..len1) {
                val char1 = s1[i - 1]
                for (j in 1..len2) {
                    val char2 = s2[j - 1]
                    val cost = if (char1 == char2) 0 else 1
                    var current = min(dp[i - 1][j] + 1, min(dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost))
                    if (i > 1 && j > 1 && char1 == s2[j - 2] && s1[i - 2] == char2) {
                        current = min(current, dp[i - 2][j - 2] + 1)
                    }
                    dp[i][j] = current
                }
            }
            return dp[len1][len2]
        }

        val testWords = listOf(
            "eminem", "rihanna", "dua", "lipa", "weekend", "weeknd",
            "sheeran", "sheran", "taylor", "swift", "ke\$ha", "kesha",
            "blinding", "lights", "levitating", "nostalgia", "recovery",
            "diamonds", "unapologetic", "future", "starboy", "afterhours"
        )

        for (w1 in testWords) {
            for (w2 in testWords) {
                val expected = referenceDamerauLevenshtein(w1, w2, 3)
                val actual = FuzzyMatcher.calculateDamerauLevenshtein(w1, w2, 3)
                if (expected <= 3) {
                    assertEquals("Mismatch for pair ($w1, $w2)", expected, actual)
                }
            }
        }
    }
}
