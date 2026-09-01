package com.aura.music.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.music.data.local.PlaylistListRow
import com.aura.music.desktop.state.DesktopAppState
import com.aura.music.desktop.ui.*
import com.aura.music.ui.theme.*

@Composable
fun DesktopSidebar(
    appState: DesktopAppState,
    playlists: List<PlaylistListRow>,
    activeDownloadsCount: Int = 0,
    onPlaylistSelected: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(260.dp)
            .fillMaxHeight()
            .background(OffBlack)
            .padding(vertical = 20.dp, horizontal = 12.dp)
    ) {
        // Logo AURA & Brand Title
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clickable { appState.navigateToRoot("home") },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(BlazeOrange)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "AURA",
                color = PureWhite,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Navigation Principale
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SidebarNavItem(
                icon = Icons.Rounded.Home,
                label = "Accueil",
                isSelected = appState.currentScreen == "home",
                onClick = { appState.navigateToRoot("home") }
            )
            SidebarNavItem(
                icon = Icons.Rounded.Search,
                label = "Rechercher",
                isSelected = appState.currentScreen == "search",
                onClick = { appState.navigateToRoot("search") }
            )
            SidebarNavItem(
                icon = Icons.Rounded.LibraryMusic,
                label = "Bibliothèque",
                isSelected = appState.currentScreen == "library",
                onClick = { appState.navigateToRoot("library") }
            )
            SidebarNavItem(
                icon = Icons.Rounded.Favorite,
                label = "Favoris",
                isSelected = appState.currentScreen == "favorites",
                iconTint = if (appState.currentScreen == "favorites") BlazeOrange else PureWhite,
                onClick = { appState.navigateToRoot("favorites") }
            )
            SidebarNavItem(
                icon = Icons.Rounded.Download,
                label = "Téléchargements",
                badgeCount = activeDownloadsCount,
                isSelected = appState.currentScreen == "downloads",
                onClick = { appState.navigateToRoot("downloads") }
            )
            SidebarNavItem(
                icon = Icons.Rounded.CloudSync,
                label = "Gestion Cloud",
                isSelected = appState.currentScreen == "cloud_sync",
                onClick = { appState.navigateToRoot("cloud_sync") }
            )
            SidebarNavItem(
                icon = Icons.Rounded.Settings,
                label = "Paramètres",
                isSelected = appState.currentScreen == "settings",
                onClick = { appState.navigateToRoot("settings") }
            )
        }

        Spacer(modifier = Modifier.height(20.dp))
        HorizontalDivider(color = HairlineDark, thickness = 1.dp)
        Spacer(modifier = Modifier.height(16.dp))

        // Section Mes Playlists
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "MES PLAYLISTS",
                color = PureWhite.copy(alpha = 0.5f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(
                    onClick = { appState.showImportPlaylistDialog = true },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FileDownload,
                        contentDescription = "Importer une playlist",
                        tint = PureWhite.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp)
                    )
                }
                IconButton(
                    onClick = { appState.showCreatePlaylistDialog = true },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = "Créer une playlist",
                        tint = PureWhite.copy(alpha = 0.7f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Liste des playlists utilisateur
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (playlists.isEmpty()) {
                item {
                    Text(
                        text = "Aucune playlist",
                        color = PureWhite.copy(alpha = 0.3f),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            } else {
                items(playlists, key = { it.id }) { pl ->
                    val isSelected = appState.currentScreen == "playlist_detail" && appState.selectedPlaylistId == pl.id
                    val interactionSource = remember { MutableInteractionSource() }
                    val isHovered by interactionSource.collectIsHoveredAsState()

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                when {
                                    isSelected -> BlazeOrange.copy(alpha = 0.15f)
                                    isHovered -> DarkGraphite.copy(alpha = 0.6f)
                                    else -> Color.Transparent
                                }
                            )
                            .hoverable(interactionSource)
                            .clickable {
                                if (onPlaylistSelected != null) {
                                    onPlaylistSelected(pl.id)
                                } else {
                                    appState.openPlaylist(pl.id)
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.QueueMusic,
                            contentDescription = null,
                            tint = if (isSelected) BlazeOrange else PureWhite.copy(alpha = 0.5f),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = pl.name,
                                color = if (isSelected) BlazeOrange else if (isHovered) PureWhite else PureWhite.copy(alpha = 0.8f),
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${pl.trackCount} titres",
                                color = PureWhite.copy(alpha = 0.4f),
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SidebarNavItem(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    iconTint: Color = PureWhite,
    badgeCount: Int = 0
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    isSelected -> DarkGraphite
                    isHovered -> DarkGraphite.copy(alpha = 0.5f)
                    else -> Color.Transparent
                }
            )
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (isSelected) BlazeOrange else iconTint.copy(alpha = if (isHovered) 1f else 0.7f),
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = label,
            color = if (isSelected) PureWhite else PureWhite.copy(alpha = if (isHovered) 1f else 0.7f),
            fontSize = 14.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )
        if (badgeCount > 0) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(BlazeOrange)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "$badgeCount",
                    color = PureWhite,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
