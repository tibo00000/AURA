package com.aura.music.ui.screens

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.aura.music.ui.RouteScaffold
import com.aura.music.data.repository.LocalLibraryRepository
import org.burnoutcrew.reorderable.ReorderableItem
import org.burnoutcrew.reorderable.detectReorderAfterLongPress
import org.burnoutcrew.reorderable.rememberReorderableLazyListState
import org.burnoutcrew.reorderable.reorderable

data class SandboxItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val coverUrl: String,
    val type: String
)

@Composable
fun SandboxScreen(
    repository: LocalLibraryRepository,
    onNavigateBack: () -> Unit
) {
    // Control panel states
    var enableReorderableModifier by remember { mutableStateOf(true) }
    var enableComplexHeaderInside by remember { mutableStateOf(false) }
    var enableAsyncCovers by remember { mutableStateOf(true) }
    var useStableKeys by remember { mutableStateOf(true) }
    var useExplicitContentTypes by remember { mutableStateOf(true) }
    var enableDragHandles by remember { mutableStateOf(true) }
    var useRealTracks by remember { mutableStateOf(false) }
    var listSize by remember { mutableFloatStateOf(100f) }
    var showSettingsPanel by remember { mutableStateOf(true) }

    // Load real tracks from database
    val realTracksState = produceState<List<com.aura.music.data.local.TrackListRow>>(initialValue = emptyList(), repository) {
        value = repository.getAllTracks()
    }
    val realTracks = realTracksState.value

    // Generate list items based on selected size and source
    val items = remember(listSize, useRealTracks, realTracks) {
        if (useRealTracks && realTracks.isNotEmpty()) {
            List(listSize.toInt()) { i ->
                val dbTrack = realTracks[i % realTracks.size]
                SandboxItem(
                    id = "real_${dbTrack.id}_$i",
                    title = dbTrack.title,
                    subtitle = dbTrack.artistName,
                    coverUrl = dbTrack.coverUri ?: "",
                    type = if (dbTrack.isLiked) "priority_item" else "main_item"
                )
            }
        } else {
            List(listSize.toInt()) { i ->
                SandboxItem(
                    id = "item_stable_id_$i",
                    title = "Sandbox Track #${i + 1}",
                    subtitle = "Artist Diagnostics ${i + 1}",
                    coverUrl = "https://picsum.photos/100?random=$i",
                    type = if (i % 5 == 0) "priority_item" else "main_item"
                )
            }
        }
    }

    var localItems by remember(items) { mutableStateOf(items) }
    LaunchedEffect(items) {
        localItems = items
    }

    val reorderState = rememberReorderableLazyListState(
        onMove = { from, to ->
            val list = localItems.toMutableList()
            val fromIdx = list.indexOfFirst { it.id == from.key }
            val toIdx = list.indexOfFirst { it.id == to.key }
            if (fromIdx != -1 && toIdx != -1) {
                list.add(toIdx, list.removeAt(fromIdx))
                localItems = list
            }
        },
        canDragOver = { draggedOver, dragging ->
            val draggingKey = dragging.key?.toString() ?: ""
            val draggedOverKey = draggedOver.key?.toString() ?: ""
            val isStaticDragging = draggingKey == "sandbox_settings_panel" || 
                                   draggingKey == "sandbox_settings_info_banner" || 
                                   draggingKey == "sandbox_artwork_header"
            val isStaticDraggedOver = draggedOverKey == "sandbox_settings_panel" || 
                                      draggedOverKey == "sandbox_settings_info_banner" || 
                                      draggedOverKey == "sandbox_artwork_header"
            !isStaticDragging && !isStaticDraggedOver
        }
    )

    RouteScaffold(
        title = "Sandbox Performance",
        onNavigateBack = onNavigateBack,
        actions = {
            IconButton(onClick = { showSettingsPanel = !showSettingsPanel }) {
                Icon(
                    imageVector = if (showSettingsPanel) Icons.Rounded.SettingsBackupRestore else Icons.Rounded.Settings,
                    contentDescription = "Basculer les réglages"
                )
            }
        }
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Heavy header rendered OUTSIDE LazyColumn if not nested inside
            if (!enableComplexHeaderInside && showSettingsPanel) {
                ComplexHeaderWidget()
            }

            // LazyColumn container
            val columnModifier = Modifier
                .fillMaxWidth()
                .weight(1f)

            val columnModifierWithReorder = if (enableReorderableModifier) {
                columnModifier.reorderable(reorderState)
            } else {
                columnModifier
            }

            LazyColumn(
                state = reorderState.listState,
                modifier = columnModifierWithReorder,
                contentPadding = PaddingValues(bottom = 120.dp)
            ) {
                // Collapsible settings panel inside LazyColumn
                if (showSettingsPanel) {
                    item(key = "sandbox_settings_panel", contentType = "sandbox_settings_panel") {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = "Paramètres de Diagnostic",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )

                                // Switches
                                SandboxSwitchRow(
                                    title = "Modifier reorderable sur LazyColumn",
                                    checked = enableReorderableModifier,
                                    onCheckedChange = { enableReorderableModifier = it }
                                )

                                SandboxSwitchRow(
                                    title = "Header complexe INSIDE LazyColumn",
                                    checked = enableComplexHeaderInside,
                                    onCheckedChange = { enableComplexHeaderInside = it }
                                )

                                SandboxSwitchRow(
                                    title = "Chargement d'images Coil",
                                    checked = enableAsyncCovers,
                                    onCheckedChange = { enableAsyncCovers = it }
                                )

                                SandboxSwitchRow(
                                    title = "Clés stables vs index",
                                    checked = useStableKeys,
                                    onCheckedChange = { useStableKeys = it }
                                )

                                SandboxSwitchRow(
                                    title = "Paramètre contentType explicite",
                                    checked = useExplicitContentTypes,
                                    onCheckedChange = { useExplicitContentTypes = it }
                                )

                                SandboxSwitchRow(
                                    title = "Poignées de déplacement (detectReorder)",
                                    checked = enableDragHandles,
                                    onCheckedChange = { enableDragHandles = it }
                                )

                                SandboxSwitchRow(
                                    title = "Utiliser les sons réels de la BDD",
                                    checked = useRealTracks,
                                    onCheckedChange = { useRealTracks = it }
                                )

                                // Slider for list size
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Taille de la liste", style = MaterialTheme.typography.bodyMedium)
                                        Text("${listSize.toInt()} éléments", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                    }
                                    Slider(
                                        value = listSize,
                                        onValueChange = { listSize = it },
                                        valueRange = 10f..500f,
                                        colors = SliderDefaults.colors(
                                            thumbColor = MaterialTheme.colorScheme.primary,
                                            activeTrackColor = MaterialTheme.colorScheme.primary
                                        )
                                    )
                                }

                                Button(
                                    onClick = { showSettingsPanel = false },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                ) {
                                    Text("Masquer pour tester le scroll")
                                }
                            }
                        }
                    }
                } else {
                    // Info banner when settings are hidden inside LazyColumn
                    item(key = "sandbox_settings_info_banner", contentType = "sandbox_settings_info_banner") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .clickable { showSettingsPanel = true }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Réglages masqués. Scroll libre.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Afficher",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Heavy header rendered INSIDE LazyColumn if enabled
                if (enableComplexHeaderInside) {
                    item(key = "sandbox_artwork_header", contentType = "sandbox_artwork_header") {
                        ComplexHeaderWidget()
                    }
                }

                // List items
                itemsIndexed(
                    items = localItems,
                    key = if (useStableKeys) { _, item -> item.id } else { index, _ -> "index_$index" },
                    contentType = if (useExplicitContentTypes) { _, item -> item.type } else { _, _ -> null }
                ) { index, item ->
                    val rowContent = @Composable { isDragging: Boolean ->
                        SandboxItemRow(
                            item = item,
                            isDragging = isDragging,
                            enableAsyncCovers = enableAsyncCovers,
                            enableDragHandles = enableDragHandles,
                            dragModifier = if (enableDragHandles && enableReorderableModifier) {
                                Modifier.detectReorderAfterLongPress(reorderState)
                            } else {
                                Modifier
                            }
                        )
                    }

                    if (enableReorderableModifier) {
                        ReorderableItem(reorderState, key = item.id) { isDragging ->
                            rowContent(isDragging)
                        }
                    } else {
                        rowContent(false)
                    }
                }
            }
        }
    }
}

@Composable
private fun SandboxSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.scale(0.8f)
        )
    }
}


@Composable
private fun ComplexHeaderWidget() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Large Artwork Simulation
        Box(
            modifier = Modifier
                .size(160.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF444444), Color(0xFF1E1E1E)))),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.Animation,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp)
            )
        }

        Text(
            text = "Artwork & Controles Complexes",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        // Progress bar simulation
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            LinearProgressIndicator(
                progress = 0.35f,
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                color = MaterialTheme.colorScheme.primary
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("01:24", style = MaterialTheme.typography.labelSmall)
                Text("03:45", style = MaterialTheme.typography.labelSmall)
            }
        }

        // Heavy layout: Transport controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {}) { Icon(Icons.Rounded.Shuffle, null) }
            IconButton(onClick = {}) { Icon(Icons.Rounded.SkipPrevious, null, modifier = Modifier.size(36.dp)) }
            IconButton(
                onClick = {},
                modifier = Modifier.size(48.dp).background(MaterialTheme.colorScheme.primary, CircleShape)
            ) {
                Icon(Icons.Rounded.PlayArrow, null, tint = MaterialTheme.colorScheme.onPrimary)
            }
            IconButton(onClick = {}) { Icon(Icons.Rounded.SkipNext, null, modifier = Modifier.size(36.dp)) }
            IconButton(onClick = {}) { Icon(Icons.Rounded.Repeat, null) }
        }
    }
}

@Composable
private fun SandboxItemRow(
    item: SandboxItem,
    isDragging: Boolean,
    enableAsyncCovers: Boolean,
    enableDragHandles: Boolean,
    dragModifier: Modifier
) {
    val elevation by animateDpAsState(if (isDragging) 8.dp else 0.dp)
    val bgColor = if (isDragging) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(elevation)
            .background(bgColor)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Thumbnail
        if (enableAsyncCovers) {
            AsyncImage(
                model = item.coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(6.dp))
            )
        } else {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Titles
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = item.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Drag handle
        if (enableDragHandles) {
            Icon(
                imageVector = Icons.Rounded.DragHandle,
                contentDescription = "Réorganiser",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = dragModifier
                    .size(40.dp)
                    .padding(8.dp)
            )
        }
    }
}
