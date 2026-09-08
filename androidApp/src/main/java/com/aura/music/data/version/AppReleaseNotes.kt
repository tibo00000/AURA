package com.aura.music.data.version

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.ui.graphics.vector.ImageVector
import com.aura.music.R

data class ReleaseNoteItem(
    val icon: ImageVector,
    @StringRes val titleRes: Int,
    @StringRes val descRes: Int,
)

data class VersionReleaseNotes(
    val versionCode: Int,
    val versionName: String,
    @StringRes val titleRes: Int = R.string.whats_new_dialog_title,
    @StringRes val subtitleRes: Int = R.string.whats_new_dialog_subtitle,
    val items: List<ReleaseNoteItem>,
)

/**
 * Catalogue central des notes de version destinées à l'expérience utilisateur.
 * Conforme à la politique de release d'AGENTS.md et BUILD.md :
 * - Uniquement des points perceptibles par l'utilisateur final.
 * - Zéro jargon technique interne.
 * - Identification via @StringRes pour supporter l'internationalisation.
 */
object AppReleaseNotes {

    private val releaseNotesMap = mapOf(
        2 to VersionReleaseNotes(
            versionCode = 2,
            versionName = "0.2.0",
            items = listOf(
                ReleaseNoteItem(
                    icon = Icons.Rounded.QueueMusic,
                    titleRes = R.string.whats_new_v2_feature1_title,
                    descRes = R.string.whats_new_v2_feature1_desc,
                ),
                ReleaseNoteItem(
                    icon = Icons.Rounded.CloudDone,
                    titleRes = R.string.whats_new_v2_feature2_title,
                    descRes = R.string.whats_new_v2_feature2_desc,
                ),
                ReleaseNoteItem(
                    icon = Icons.Rounded.AutoAwesome,
                    titleRes = R.string.whats_new_v2_feature3_title,
                    descRes = R.string.whats_new_v2_feature3_desc,
                ),
                ReleaseNoteItem(
                    icon = Icons.Rounded.Bolt,
                    titleRes = R.string.whats_new_v2_feature4_title,
                    descRes = R.string.whats_new_v2_feature4_desc,
                ),
            ),
        ),
    )

    /**
     * Récupère les notes de version pour un versionCode donné.
     */
    fun getNotesForVersion(versionCode: Int): VersionReleaseNotes? {
        return releaseNotesMap[versionCode]
    }

    /**
     * Récupère les notes les plus récentes disponibles dans le catalogue.
     */
    fun getLatestNotes(): VersionReleaseNotes {
        return releaseNotesMap.values.maxByOrNull { it.versionCode }
            ?: VersionReleaseNotes(
                versionCode = 2,
                versionName = "0.2.0",
                items = emptyList(),
            )
    }
}
