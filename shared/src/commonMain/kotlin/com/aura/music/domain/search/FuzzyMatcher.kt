package com.aura.music.domain.search

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Moteur de calcul flou et tolérance aux coquilles (Damerau-Levenshtein).
 *
 * Implémentation haute performance adaptée aux appareils mobiles :
 * - Prise en compte des transpositions de caractères adjacents (ex: "lpipa" -> "lipa")
 * - Court-circuit immédiat sur la longueur
 * - Tolérance adaptative proportionnelle à la taille du mot
 */
object FuzzyMatcher {

    /**
     * Détermine si un token de requête correspond de façon floue à un token candidat.
     * Règle de tolérance adaptative :
     * - Longueur < 4 : match exact uniquement (distance 0)
     * - Longueur 4..6 : tolérance de 1 faute (distance <= 1)
     * - Longueur >= 7 : tolérance de 2 fautes (distance <= 2)
     */
    fun isFuzzyMatch(queryToken: String, candidateToken: String): Boolean {
        if (queryToken == candidateToken) return true
        if (candidateToken.startsWith(queryToken)) return true

        val qLen = queryToken.length
        val cLen = candidateToken.length

        val maxAllowedDistance = when {
            qLen < 4 -> 0
            qLen <= 6 -> 1
            else -> 2
        }

        if (maxAllowedDistance == 0) return false
        if (abs(qLen - cLen) > maxAllowedDistance) return false

        val distance = calculateDamerauLevenshtein(queryToken, candidateToken, maxAllowedDistance)
        return distance <= maxAllowedDistance
    }

    /**
     * Calcule un score de similarité normalisé entre 0.0f et 1.0f.
     */
    fun fuzzySimilarity(queryToken: String, candidateToken: String): Float {
        if (queryToken == candidateToken) return 1.0f
        if (candidateToken.startsWith(queryToken)) {
            return 0.85f + (0.15f * (queryToken.length.toFloat() / candidateToken.length))
        }

        val maxLen = max(queryToken.length, candidateToken.length)
        if (maxLen == 0) return 1.0f

        val maxDistance = when {
            queryToken.length < 4 -> 0
            queryToken.length <= 6 -> 1
            else -> 2
        }

        if (maxDistance == 0) return 0.0f

        val dist = calculateDamerauLevenshtein(queryToken, candidateToken, maxDistance)
        if (dist > maxDistance) return 0.0f

        return 1.0f - (dist.toFloat() / maxLen)
    }

    /**
     * Distance de Damerau-Levenshtein avec court-circuit précoce pour performance O(N*M) minimale.
     */
    fun calculateDamerauLevenshtein(s1: String, s2: String, maxDistance: Int): Int {
        val len1 = s1.length
        val len2 = s2.length

        if (abs(len1 - len2) > maxDistance) return maxDistance + 1
        if (len1 == 0) return len2
        if (len2 == 0) return len1

        // Matrice de dimension (len1 + 1) x (len2 + 1)
        val dp = Array(len1 + 1) { IntArray(len2 + 1) }

        for (i in 0..len1) dp[i][0] = i
        for (j in 0..len2) dp[0][j] = j

        for (i in 1..len1) {
            var minRowCost = dp[i][0]
            val char1 = s1[i - 1]

            for (j in 1..len2) {
                val char2 = s2[j - 1]
                val cost = if (char1 == char2) 0 else 1

                var current = min(
                    dp[i - 1][j] + 1,      // suppression
                    min(
                        dp[i][j - 1] + 1,  // insertion
                        dp[i - 1][j - 1] + cost // substitution
                    )
                )

                // Transposition de caractères adjacents
                if (i > 1 && j > 1 && char1 == s2[j - 2] && s1[i - 2] == char2) {
                    current = min(current, dp[i - 2][j - 2] + 1)
                }

                dp[i][j] = current
                minRowCost = min(minRowCost, current)
            }

            // Si la meilleure distance possible sur cette ligne dépasse maxDistance, arrêt prématuré
            if (minRowCost > maxDistance) {
                return maxDistance + 1
            }
        }

        return dp[len1][len2]
    }
}
