package com.aura.music.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Ordered catalogue credits. A name containing punctuation is a single artist. */
@Serializable
data class ArtistCreditDto(
    val id: String,
    val name: String,
    val provider: String? = null,
    @SerialName("provider_id") val providerId: String? = null,
    val role: String = "performer",
    @SerialName("picture_uri") val pictureUri: String? = null,
)
