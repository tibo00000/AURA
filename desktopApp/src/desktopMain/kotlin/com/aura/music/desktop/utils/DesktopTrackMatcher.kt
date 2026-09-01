package com.aura.music.desktop.utils

import com.aura.music.data.local.TrackListRow
import com.aura.music.data.network.TrackSummary
import com.aura.music.domain.search.SearchNormalizer
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

object DesktopTrackMatcher {
    private val idCache = ConcurrentHashMap<String, String>()

    fun extractDeezerId(id: String?): String? {
        if (id.isNullOrBlank()) return null
        val cached = idCache[id]
        if (cached != null) return if (cached.isEmpty()) null else cached

        val resolved = computeDeezerId(id)
        idCache[id] = resolved ?: ""
        return resolved
    }

    private fun computeDeezerId(id: String): String? {
        if (id.startsWith("trk_")) {
            return try {
                val token = id.removePrefix("trk_")
                val padding = when (token.length % 4) {
                    2 -> "=="
                    3 -> "="
                    else -> ""
                }
                val paddedToken = token + padding
                val bytes = Base64.getUrlDecoder().decode(paddedToken)
                val decoded = String(bytes, Charsets.UTF_8)
                val parts = decoded.split(":")
                if (parts.size >= 4 && parts[2].equals("deezer", ignoreCase = true)) parts[3].trim() else null
            } catch (e: Exception) {
                null
            }
        } else if (id.startsWith("deezer:", ignoreCase = true)) {
            return id.substringAfter(":").trim()
        } else if (id.all { it.isDigit() }) {
            return id.trim()
        }
        return null
    }

    fun isDeezerTrackMatch(trackIdA: String?, trackIdB: String?): Boolean {
        if (trackIdA.isNullOrBlank() || trackIdB.isNullOrBlank()) return false
        if (trackIdA == trackIdB) return true
        val deezerIdA = extractDeezerId(trackIdA)
        val deezerIdB = extractDeezerId(trackIdB)
        return deezerIdA != null && deezerIdB != null && deezerIdA == deezerIdB
    }

    fun isTrackMatch(local: TrackListRow, online: TrackSummary): Boolean {
        if (local.id == online.id) return true
        if (isDeezerTrackMatch(local.id, online.id)) return true

        val localTitleNorm = SearchNormalizer.normalize(local.title)
        val onlineTitleNorm = SearchNormalizer.normalize(online.title)
        val localArtistNorm = SearchNormalizer.normalize(local.artistName)
        val onlineArtistNorm = SearchNormalizer.normalize(online.displayArtistName)

        if (localTitleNorm.isNotBlank() && localTitleNorm == onlineTitleNorm &&
            localArtistNorm.isNotBlank() && localArtistNorm == onlineArtistNorm) {
            return true
        }

        return false
    }

    fun findMatchingLocalTrack(allTracks: List<TrackListRow>, online: TrackSummary): TrackListRow? {
        return allTracks.firstOrNull { isTrackMatch(it, online) }
    }
}
