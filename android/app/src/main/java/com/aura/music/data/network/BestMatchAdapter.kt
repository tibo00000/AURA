package com.aura.music.data.network

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import java.lang.reflect.Type

class BestMatchAdapter(moshi: Moshi) : JsonAdapter<BestMatch>() {
    private val trackAdapter: JsonAdapter<TrackSummary> = moshi.adapter(TrackSummary::class.java)
    private val artistAdapter: JsonAdapter<ArtistSummary> = moshi.adapter(ArtistSummary::class.java)
    private val albumAdapter: JsonAdapter<AlbumSummary> = moshi.adapter(AlbumSummary::class.java)

    override fun fromJson(reader: JsonReader): BestMatch? {
        if (reader.peek() == JsonReader.Token.NULL) {
            return reader.nextNull()
        }

        var kind: String? = null
        var item: Any? = null

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.selectName(JsonReader.Options.of("kind", "item"))) {
                0 -> {
                    kind = reader.nextString()
                }
                1 -> {
                    item = reader.readJsonValue() // Moshi reads nested structures as Map/List
                }
                -1 -> {
                    reader.skipName()
                    reader.skipValue()
                }
            }
        }
        reader.endObject()

        if (kind == null) return null

        val typedItem = if (item is Map<*, *>) {
            when (kind) {
                "track" -> trackAdapter.fromJsonValue(item)
                "artist" -> artistAdapter.fromJsonValue(item)
                "album" -> albumAdapter.fromJsonValue(item)
                else -> null
            }
        } else {
            null
        }

        return BestMatch(kind = kind, item = typedItem)
    }

    override fun toJson(writer: JsonWriter, value: BestMatch?) {
        if (value == null) {
            writer.nullValue()
            return
        }
        writer.beginObject()
        writer.name("kind").value(value.kind)
        writer.name("item")
        when (value.kind) {
            "track" -> trackAdapter.toJson(writer, value.item as? TrackSummary)
            "artist" -> artistAdapter.toJson(writer, value.item as? ArtistSummary)
            "album" -> albumAdapter.toJson(writer, value.item as? AlbumSummary)
            else -> writer.nullValue()
        }
        writer.endObject()
    }

    companion object {
        val FACTORY = object : JsonAdapter.Factory {
            override fun create(type: Type, annotations: Set<Annotation>, moshi: Moshi): JsonAdapter<*>? {
                if (type == BestMatch::class.java) {
                    return BestMatchAdapter(moshi)
                }
                return null
            }
        }
    }
}
