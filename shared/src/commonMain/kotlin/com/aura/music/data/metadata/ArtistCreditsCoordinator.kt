package com.aura.music.data.metadata

import com.aura.music.data.local.ArtistEntity
import com.aura.music.data.local.AuraDatabase
import com.aura.music.data.local.TrackArtistEntity
import com.aura.music.data.local.TrackEntity
import com.aura.music.data.network.ArtistCreditDto

/** Prepared in IO, applied inside the caller's existing immediate transaction. */
class ArtistCreditsPlan internal constructor(
    private val newArtists: Map<String, ArtistEntity>,
    private val changes: List<Change>,
    private val now: Long,
    private val renames: List<ArtistRename> = emptyList(),
) {
    internal data class ArtistRename(val id: String, val expectedName: String, val name: String)
    internal data class Change(val expected: TrackEntity, val credits: List<TrackArtistEntity>, val label: String)
    val hasChanges: Boolean get() = changes.isNotEmpty() || renames.isNotEmpty()

    suspend fun apply(database: AuraDatabase, isCurrent: () -> Boolean) {
        if (!isCurrent()) return
        val dao = database.trackArtistDao()
        for (rename in renames) {
            if (!isCurrent()) return
            database.artistDao().repairCombinedArtistName(rename.id, rename.expectedName, rename.name, rename.name.lowercase().trim(), now)
        }
        for (change in changes) {
            if (!isCurrent()) return
            val live = database.trackDao().getRawTrackById(change.expected.id) ?: continue
            val previous = dao.getCredits(live.id)
            if (previous.any { it.origin == "manual" }) continue
            // A concurrent manual rename/artist edit must not be replaced by this plan.
            if (live.displayArtistName != change.expected.displayArtistName || live.primaryArtistId != change.expected.primaryArtistId) continue
            val wanted = change.credits
            if (wanted != previous) {
                database.artistDao().insertArtistsIgnore(wanted.mapNotNull { newArtists[it.artistId] })
                dao.deleteCredits(live.id)
                dao.insertCredits(wanted)
            }
            val first = wanted.first().artistId
            val updated = live.copy(primaryArtistId = first, displayArtistName = change.label)
            if (updated != live) database.trackDao().upsertTrack(updated.copy(updatedAt = maxOf(live.updatedAt, now)))
        }
    }
}

suspend fun prepareArtistCredits(
    database: AuraDatabase, tracks: Map<String, TrackEntity>, incoming: Map<String, List<ArtistCreditDto>>, now: Long,
): ArtistCreditsPlan {
    if (incoming.isEmpty()) return ArtistCreditsPlan(emptyMap(), emptyList(), now)
    val dao = database.trackArtistDao()
    val existingArtists = dao.getArtistIdentities()
    val byId = existingArtists.associateBy { it.id }.toMutableMap()
    val byName = existingArtists.groupBy { it.name.trim().lowercase() }
    val existingCredits = dao.getAllCredits().groupBy { it.trackId }
    val created = linkedMapOf<String, ArtistEntity>()
    val changes = mutableListOf<ArtistCreditsPlan.Change>()
    val renames = linkedMapOf<String, ArtistCreditsPlan.ArtistRename>()
    for ((id, raw) in incoming) {
        val track = tracks[id] ?: continue
        val credits = raw.filter { it.id.isNotBlank() && it.name.isNotBlank() }.distinctBy { it.id }.take(32)
        if (credits.isEmpty()) continue
        val previous = existingCredits[id].orEmpty()
        if (previous.any { it.origin == "manual" }) continue
        val usedArtistIds = hashSetOf<String>()
        val resolved = credits.map { credit ->
            // Reuse an unambiguous exact legacy artist label; never split it into names.
            val existing = byId[credit.id] ?: byName[credit.name.trim().lowercase()]?.singleOrNull()?.takeIf { it.id !in usedArtistIds }
            val artist = existing ?: created.getOrPut(credit.id) {
                ArtistEntity(credit.id, credit.name, credit.name.lowercase().trim(),
                    pictureUri = credit.pictureUri, createdAt = now, updatedAt = now)
            }
            byId[credit.id] = artist
            usedArtistIds.add(artist.id)
            artist to credit
        }.distinctBy { it.first.id }
        val wanted = resolved.mapIndexed { index, (artist, credit) ->
            TrackArtistEntity(id, artist.id, index, if (index == 0) "primary" else credit.role, "catalogue")
        }
        // Search results and old clients may only know the principal. Preserve
        // already known guest credits when such a partial list arrives later.
        if (wanted.size < previous.size && wanted.all { fresh -> previous.any { it.artistId == fresh.artistId } }) continue
        val label = credits.joinToString(", ") { it.name }
        // A title that still has a custom scalar artist label keeps that label.
        val allowed = setOf(credits.first().name, label, "Téléchargement", "Artiste Inconnu", "Artiste inconnu", "Unknown artist", "Unknown Artist", "")
        if (track.displayArtistName !in allowed) continue // Preserve a custom legacy artist selection.
        val display = label
        if (credits.size > 1) for ((artist, credit) in resolved) {
            // Repair a legacy concatenated page only with the same verified canonical ID.
            // A genuine comma in an individual artist name and custom names are preserved.
            if (artist.id == credit.id && artist.name == label && artist.name != credit.name) {
                renames[artist.id] = ArtistCreditsPlan.ArtistRename(artist.id, artist.name, credit.name)
            }
        }
        if (wanted != previous || track.primaryArtistId != wanted.first().artistId || display != track.displayArtistName) {
            changes += ArtistCreditsPlan.Change(track, wanted, display)
        }
    }
    return ArtistCreditsPlan(created, changes, now, renames.values.toList())
}
