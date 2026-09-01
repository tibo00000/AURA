package com.aura.music.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.aura.music.desktop.ui.*
import com.aura.music.ui.theme.*
import java.io.File

@Composable
fun DesktopArtworkCover(
    coverUri: String?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    shapeRadius: Dp = 6.dp,
    fallbackIcon: ImageVector = Icons.Rounded.MusicNote
) {
    val resolvedModel = androidx.compose.runtime.remember(coverUri) {
        when {
            coverUri.isNullOrBlank() -> null
            coverUri.startsWith("http://") || coverUri.startsWith("https://") -> coverUri
            coverUri.startsWith("file:") -> coverUri
            coverUri.startsWith("content://") -> null
            else -> try {
                val f = File(coverUri)
                if (f.exists()) f.toURI().toString() else null
            } catch (e: Exception) {
                null
            }
        }
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(shapeRadius))
            .background(DarkGraphite),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = fallbackIcon,
            contentDescription = null,
            tint = PureWhite.copy(alpha = 0.25f),
            modifier = Modifier.size(size * 0.45f)
        )
        if (resolvedModel != null) {
            AsyncImage(
                model = resolvedModel,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
fun DesktopPlaylistMosaicCover(
    covers: List<String>,
    modifier: Modifier = Modifier,
    size: Dp = 160.dp,
    shapeRadius: Dp = 12.dp
) {
    val cleanCovers = covers.filter { it.isNotBlank() }.take(4)

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(shapeRadius))
            .background(DarkGraphite),
        contentAlignment = Alignment.Center
    ) {
        when {
            cleanCovers.size >= 4 -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(modifier = Modifier.weight(1f)) {
                        DesktopArtworkCover(cleanCovers[0], modifier = Modifier.weight(1f).fillMaxHeight(), size = size / 2, shapeRadius = 0.dp)
                        DesktopArtworkCover(cleanCovers[1], modifier = Modifier.weight(1f).fillMaxHeight(), size = size / 2, shapeRadius = 0.dp)
                    }
                    Row(modifier = Modifier.weight(1f)) {
                        DesktopArtworkCover(cleanCovers[2], modifier = Modifier.weight(1f).fillMaxHeight(), size = size / 2, shapeRadius = 0.dp)
                        DesktopArtworkCover(cleanCovers[3], modifier = Modifier.weight(1f).fillMaxHeight(), size = size / 2, shapeRadius = 0.dp)
                    }
                }
            }
            cleanCovers.isNotEmpty() -> {
                DesktopArtworkCover(cleanCovers.first(), modifier = Modifier.fillMaxSize(), size = size, shapeRadius = shapeRadius)
            }
            else -> {
                Icon(
                    imageVector = Icons.Rounded.QueueMusic,
                    contentDescription = null,
                    tint = PureWhite.copy(alpha = 0.3f),
                    modifier = Modifier.size(size * 0.4f)
                )
            }
        }
    }
}
