package com.aura.music.domain.search

import com.aura.music.data.local.AlbumBrowseRow
import com.aura.music.data.local.ArtistBrowseRow
import com.aura.music.data.local.TrackListRow
import kotlin.math.max
import kotlin.math.min

/**
 * Moteur de recherche locale Zero-Jank haute performance pour AURA.
 *
 * Implémente une architecture biphasique :
 * 1. Réduction de candidats O(1) via index inversé et préfixes
 * 2. Scoring multi-tokens pondéré, tolérant aux coquilles et normalisé [0.0, 1000.0]
 */
object LocalSearchEngine {

    private const val MIN_SCORE_THRESHOLD = 50.0f
    private const val MIN_AND_CANDIDATES_THRESHOLD = 15

    /**
     * Recherche des pistes dans l'index local.
     * Injection dynamique des likes et téléchargements pour mise à jour à 0 ms sans rebuild.
     */
    fun searchTracks(
        index: LocalSearchIndex,
        query: String,
        limit: Int = 20,
        likedTrackIds: Set<String> = emptySet(),
        downloadedTrackIds: Set<String> = emptySet()
    ): List<TrackListRow> {
        val normalizedQuery = SearchNormalizer.normalize(query)
        val queryTokens = SearchNormalizer.tokenize(query)

        if (normalizedQuery.isBlank() || queryTokens.isEmpty() || index.tracks.isEmpty()) {
            return emptyList()
        }

        // ÉTAPE 1 : Réduction de candidats
        val candidateDocIndices = getTrackCandidates(index, queryTokens)
        if (candidateDocIndices.isEmpty()) {
            return emptyList()
        }

        // ÉTAPE 2 : Scoring fin multi-tokens
        val scoredList = mutableListOf<ScoredTrack>()

        for (docIdx in candidateDocIndices) {
            val doc = index.tracks[docIdx]
            val score = scoreTrack(
                doc = doc,
                normalizedQuery = normalizedQuery,
                queryTokens = queryTokens,
                isLiked = likedTrackIds.contains(doc.id) || doc.track.isLiked,
                isDownloaded = downloadedTrackIds.contains(doc.id) || !doc.track.contentUri.isNullOrBlank()
            )

            if (score >= MIN_SCORE_THRESHOLD) {
                scoredList.add(
                    ScoredTrack(
                        track = if (likedTrackIds.isNotEmpty()) {
                            doc.track.copy(isLiked = likedTrackIds.contains(doc.id))
                        } else {
                            doc.track
                        },
                        score = score
                    )
                )
            }
        }

        return scoredList
            .sortedByDescending { it.score }
            .take(limit)
            .map { it.track }
    }

    /**
     * Recherche des artistes dans l'index local.
     */
    fun searchArtists(
        index: LocalSearchIndex,
        query: String,
        limit: Int = 10
    ): List<ArtistBrowseRow> {
        val normalizedQuery = SearchNormalizer.normalize(query)
        val queryTokens = SearchNormalizer.tokenize(query)

        if (normalizedQuery.isBlank() || queryTokens.isEmpty() || index.artists.isEmpty()) {
            return emptyList()
        }

        val candidateIndices = getArtistCandidates(index, queryTokens)
        if (candidateIndices.isEmpty()) return emptyList()

        val scoredList = mutableListOf<ScoredArtist>()

        for (docIdx in candidateIndices) {
            val doc = index.artists[docIdx]
            val score = scoreArtist(doc, normalizedQuery, queryTokens)

            if (score >= MIN_SCORE_THRESHOLD) {
                scoredList.add(ScoredArtist(doc.artist, score))
            }
        }

        return scoredList
            .sortedByDescending { it.score }
            .take(limit)
            .map { it.artist }
    }

    /**
     * Recherche des albums dans l'index local.
     */
    fun searchAlbums(
        index: LocalSearchIndex,
        query: String,
        limit: Int = 10
    ): List<AlbumBrowseRow> {
        val normalizedQuery = SearchNormalizer.normalize(query)
        val queryTokens = SearchNormalizer.tokenize(query)

        if (normalizedQuery.isBlank() || queryTokens.isEmpty() || index.albums.isEmpty()) {
            return emptyList()
        }

        val candidateIndices = getAlbumCandidates(index, queryTokens)
        if (candidateIndices.isEmpty()) return emptyList()

        val scoredList = mutableListOf<ScoredAlbum>()

        for (docIdx in candidateIndices) {
            val doc = index.albums[docIdx]
            val score = scoreAlbum(doc, normalizedQuery, queryTokens)

            if (score >= MIN_SCORE_THRESHOLD) {
                scoredList.add(ScoredAlbum(doc.album, score))
            }
        }

        return scoredList
            .sortedByDescending { it.score }
            .take(limit)
            .map { it.album }
    }

    // =========================================================================
    // RÉDUCTION DE CANDIDATS (ÉTAPE 1 : Logique Biphasique AND / OR)
    // =========================================================================

    private fun getTrackCandidates(
        index: LocalSearchIndex,
        queryTokens: List<String>
    ): Set<Int> {
        val tokenCandidateSets = mutableListOf<Set<Int>>()

        for (token in queryTokens) {
            val matchedIndices = mutableSetOf<Int>()

            // 1. Recherche par préfixe / token exact
            val exactOrPrefix = index.trackPrefixIndex[token]
            if (exactOrPrefix != null) {
                for (id in exactOrPrefix) matchedIndices.add(id)
            }

            // Si le token fait plus de 2 lettres et n'a rien trouvé, essayer les sous-préfixes
            if (matchedIndices.isEmpty() && token.length >= 3) {
                val sub3 = index.trackPrefixIndex[token.substring(0, 3)]
                if (sub3 != null) for (id in sub3) matchedIndices.add(id)
            }

            // 2. Recherche floue sélective sur le vocabulaire unique
            if (matchedIndices.isEmpty() && !SearchNormalizer.isStopword(token)) {
                for (vocabToken in index.trackVocabulary) {
                    if (FuzzyMatcher.isFuzzyMatch(token, vocabToken)) {
                        val vocabDocs = index.trackPrefixIndex[vocabToken]
                        if (vocabDocs != null) {
                            for (id in vocabDocs) matchedIndices.add(id)
                        }
                    }
                }
            }

            if (matchedIndices.isNotEmpty()) {
                tokenCandidateSets.add(matchedIndices)
            }
        }

        if (tokenCandidateSets.isEmpty()) {
            return emptySet()
        }

        // Intersection AND stricte
        var intersection = tokenCandidateSets.first()
        for (i in 1 until tokenCandidateSets.size) {
            intersection = intersection.intersect(tokenCandidateSets[i])
            if (intersection.isEmpty()) break
        }

        // Si l'intersection AND est suffisante (>= 15), on la prend pour précision maximale
        if (intersection.size >= MIN_AND_CANDIDATES_THRESHOLD) {
            return intersection
        }

        // Sinon, repli sur l'union OR (pour garantir de ne jamais renvoyer 0 résultat inutilement)
        val union = mutableSetOf<Int>()
        union.addAll(intersection)
        for (set in tokenCandidateSets) {
            union.addAll(set)
        }
        return union
    }

    private fun getArtistCandidates(
        index: LocalSearchIndex,
        queryTokens: List<String>
    ): Set<Int> {
        val candidates = mutableSetOf<Int>()
        for (token in queryTokens) {
            val docs = index.artistPrefixIndex[token]
            if (docs != null) {
                for (id in docs) candidates.add(id)
            }
            if (!SearchNormalizer.isStopword(token)) {
                for (vocab in index.artistVocabulary) {
                    if (FuzzyMatcher.isFuzzyMatch(token, vocab)) {
                        index.artistPrefixIndex[vocab]?.forEach { candidates.add(it) }
                    }
                }
            }
        }
        return candidates
    }

    private fun getAlbumCandidates(
        index: LocalSearchIndex,
        queryTokens: List<String>
    ): Set<Int> {
        val candidates = mutableSetOf<Int>()
        for (token in queryTokens) {
            val docs = index.albumPrefixIndex[token]
            if (docs != null) {
                for (id in docs) candidates.add(id)
            }
            if (!SearchNormalizer.isStopword(token)) {
                for (vocab in index.albumVocabulary) {
                    if (FuzzyMatcher.isFuzzyMatch(token, vocab)) {
                        index.albumPrefixIndex[vocab]?.forEach { candidates.add(it) }
                    }
                }
            }
        }
        return candidates
    }

    // =========================================================================
    // SCORING FIN MULTI-TOKENS (ÉTAPE 2 : Borné [0.0, 1000.0])
    // =========================================================================

    private fun scoreTrack(
        doc: IndexedTrack,
        normalizedQuery: String,
        queryTokens: List<String>,
        isLiked: Boolean,
        isDownloaded: Boolean
    ): Float {
        var score = 0.0f

        // 1. Correspondance exacte intégrale
        if (doc.normalizedTitle == normalizedQuery) score += 1000.0f
        else if (doc.normalizedArtist == normalizedQuery) score += 850.0f
        else if (doc.normalizedAlbum == normalizedQuery) score += 600.0f

        // 2. Préfixe direct de titre / artiste
        if (doc.normalizedTitle.startsWith(normalizedQuery)) score += 400.0f
        if (doc.normalizedArtist.startsWith(normalizedQuery)) score += 300.0f

        // 3. Sous-chaîne contiguë
        if (doc.normalizedTitle.contains(normalizedQuery)) score += 200.0f
        if (doc.normalizedArtist.contains(normalizedQuery)) score += 150.0f

        // 4. Évaluation multi-tokens par champ
        var matchedTokensCount = 0
        var tokenScoreSum = 0.0f

        for (qToken in queryTokens) {
            val isStop = SearchNormalizer.isStopword(qToken)
            val weight = if (isStop) 0.25f else 1.0f

            var bestTokenScore = 0.0f

            // Titre
            for (tToken in doc.titleTokens) {
                val sim = FuzzyMatcher.fuzzySimilarity(qToken, tToken)
                if (sim >= 0.7f) {
                    val s = (sim * 250.0f) * weight
                    bestTokenScore = max(bestTokenScore, s)
                }
            }

            // Artiste
            for (aToken in doc.artistTokens) {
                val sim = FuzzyMatcher.fuzzySimilarity(qToken, aToken)
                if (sim >= 0.7f) {
                    val s = (sim * 180.0f) * weight
                    bestTokenScore = max(bestTokenScore, s)
                }
            }

            // Album
            for (alToken in doc.albumTokens) {
                val sim = FuzzyMatcher.fuzzySimilarity(qToken, alToken)
                if (sim >= 0.7f) {
                    val s = (sim * 120.0f) * weight
                    bestTokenScore = max(bestTokenScore, s)
                }
            }

            if (bestTokenScore > 0.0f) {
                matchedTokensCount++
                tokenScoreSum += bestTokenScore
            }
        }

        // Bonus de couverture multi-tokens (ex: "eminem rihanna" -> 2/2 = 1.0)
        val coverageRatio = matchedTokensCount.toFloat() / queryTokens.size
        score += tokenScoreSum * (0.5f + (0.5f * coverageRatio))

        // Si 100% des tokens sont trouvés
        if (coverageRatio >= 1.0f && queryTokens.size > 1) {
            score += 350.0f
        }

        // 5. Affinité dynamique (favoris & téléchargés)
        if (isLiked) score += 30.0f
        if (isDownloaded) score += 20.0f

        return min(1000.0f, score)
    }

    private fun scoreArtist(
        doc: IndexedArtist,
        normalizedQuery: String,
        queryTokens: List<String>
    ): Float {
        var score = 0.0f

        if (doc.normalizedName == normalizedQuery) score += 1000.0f
        if (doc.normalizedName.startsWith(normalizedQuery)) score += 450.0f
        if (doc.normalizedName.contains(normalizedQuery)) score += 250.0f

        var matched = 0
        for (qToken in queryTokens) {
            val isStop = SearchNormalizer.isStopword(qToken)
            val weight = if (isStop) 0.3f else 1.0f

            for (aToken in doc.nameTokens) {
                val sim = FuzzyMatcher.fuzzySimilarity(qToken, aToken)
                if (sim >= 0.7f) {
                    matched++
                    score += (sim * 250.0f) * weight
                }
            }
        }

        if (matched >= queryTokens.size && queryTokens.size > 1) {
            score += 300.0f
        }

        return min(1000.0f, score)
    }

    private fun scoreAlbum(
        doc: IndexedAlbum,
        normalizedQuery: String,
        queryTokens: List<String>
    ): Float {
        var score = 0.0f

        if (doc.normalizedTitle == normalizedQuery) score += 1000.0f
        if (doc.normalizedTitle.startsWith(normalizedQuery)) score += 400.0f
        if (doc.normalizedTitle.contains(normalizedQuery)) score += 200.0f

        var matched = 0
        for (qToken in queryTokens) {
            val isStop = SearchNormalizer.isStopword(qToken)
            val weight = if (isStop) 0.3f else 1.0f

            for (tToken in doc.allTokens) {
                val sim = FuzzyMatcher.fuzzySimilarity(qToken, tToken)
                if (sim >= 0.7f) {
                    matched++
                    score += (sim * 200.0f) * weight
                }
            }
        }

        if (matched >= queryTokens.size && queryTokens.size > 1) {
            score += 250.0f
        }

        return min(1000.0f, score)
    }

    private data class ScoredTrack(val track: TrackListRow, val score: Float)
    private data class ScoredArtist(val artist: ArtistBrowseRow, val score: Float)
    private data class ScoredAlbum(val album: AlbumBrowseRow, val score: Float)
}
