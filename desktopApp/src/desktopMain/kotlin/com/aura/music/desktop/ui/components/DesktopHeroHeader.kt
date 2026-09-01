package com.aura.music.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.music.desktop.ui.*
import com.aura.music.ui.theme.*

@Composable
fun DesktopHeroHeader(
    tag: String,
    title: String,
    subtitle: String,
    coverUri: String?,
    mosaicCovers: List<String> = emptyList(),
    isLiked: Boolean = false,
    onBack: (() -> Unit)? = null,
    onPlayAll: () -> Unit,
    onShuffleAll: () -> Unit,
    onToggleLike: (() -> Unit)? = null,
    onMoreOptions: (() -> Unit)? = null,
    extraMetadata: String? = null,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        DarkGraphite.copy(alpha = 0.8f),
                        DeepBlack
                    )
                )
            )
            .padding(24.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (onBack != null) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowBack,
                        contentDescription = "Retour",
                        tint = PureWhite,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom
            ) {
                // Pochette Hero (Mosaïque ou Image unique)
                if (mosaicCovers.size >= 4) {
                    DesktopPlaylistMosaicCover(
                        covers = mosaicCovers,
                        size = 180.dp,
                        shapeRadius = 12.dp
                    )
                } else {
                    DesktopArtworkCover(
                        coverUri = coverUri,
                        size = 180.dp,
                        shapeRadius = 12.dp
                    )
                }

                Spacer(modifier = Modifier.width(28.dp))

                // Métadonnées & Titres
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = tag.uppercase(),
                        color = BlazeOrange,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = title,
                        color = PureWhite,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = subtitle,
                        color = PureWhite.copy(alpha = 0.8f),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    if (extraMetadata != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = extraMetadata,
                            color = PureWhite.copy(alpha = 0.5f),
                            fontSize = 12.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Ligne d'actions rapides
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Play All Button (56dp BlazeOrange)
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(BlazeOrange)
                                .clickable(onClick = onPlayAll),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = "Tout lire",
                                tint = PureWhite,
                                modifier = Modifier.size(32.dp)
                            )
                        }

                        // Shuffle Button (44dp DarkGraphite)
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(DarkGraphite)
                                .clickable(onClick = onShuffleAll),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Shuffle,
                                contentDescription = "Lecture aléatoire",
                                tint = PureWhite,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        // Favorite Button
                        if (onToggleLike != null) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(DarkGraphite)
                                    .clickable(onClick = onToggleLike),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                    contentDescription = "Favori",
                                    tint = if (isLiked) BlazeOrange else PureWhite,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }

                        // More Options
                        if (onMoreOptions != null) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(DarkGraphite)
                                    .clickable(onClick = onMoreOptions),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.MoreHoriz,
                                    contentDescription = "Plus d'options",
                                    tint = PureWhite,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
