package com.aura.music.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aura.music.data.local.TrackListRow
import com.aura.music.data.repository.LocalLibraryRepository
import com.aura.music.domain.player.PlayerEvent
import com.aura.music.ui.RouteScaffold
import com.aura.music.ui.components.ShimmerTrackList
import com.aura.music.ui.player.PlayerViewModel
import com.aura.music.ui.theme.*
import com.aura.music.ui.toQueuedTrack
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun SandboxScreen(
    repository: LocalLibraryRepository,
    onNavigateBack: () -> Unit,
    playerViewModel: PlayerViewModel? = null
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Scroll calibration parameters
    var maxFlingVelocity by remember { mutableFloatStateOf(3000f) } // px/s
    var scrollDamping by remember { mutableFloatStateOf(1.0f) } // 0.2 to 1.0
    var decayFriction by remember { mutableFloatStateOf(1.0f) } // 0.5 to 3.0
    var itemSourceMode by remember { mutableIntStateOf(0) } // 0: Vrais favoris, 1: 100 pistes, 2: 300 pistes
    var showCoverImages by remember { mutableStateOf(true) }
    var isSettingsPanelOpen by remember { mutableStateOf(true) }

    // Live diagnostics
    var lastRecordedFlingVelocity by remember { mutableFloatStateOf(0f) }
    var flingCount by remember { mutableIntStateOf(0) }

    // Real tracks from DB
    val likedTracksState = produceState<List<TrackListRow>?>(initialValue = null, repository) {
        value = repository.getLikedTracks()
    }
    val allTracksState = produceState<List<TrackListRow>?>(initialValue = null, repository) {
        value = repository.getAllTracks()
    }

    val displayTracks = remember(itemSourceMode, likedTracksState.value, allTracksState.value) {
        val liked = likedTracksState.value ?: emptyList()
        val all = allTracksState.value ?: emptyList()
        when (itemSourceMode) {
            0 -> if (liked.isNotEmpty()) liked else all
            1 -> {
                val pool = if (liked.isNotEmpty()) liked else all
                if (pool.isEmpty()) emptyList() else List(100) { i -> pool[i % pool.size].copy(id = "sandbox_100_$i") }
            }
            2 -> {
                val pool = if (liked.isNotEmpty()) liked else all
                if (pool.isEmpty()) emptyList() else List(300) { i -> pool[i % pool.size].copy(id = "sandbox_300_$i") }
            }
            else -> liked
        }
    }

    // Custom FlingBehavior
    val customFlingBehavior = rememberCappedFlingBehavior(
        maxVelocity = maxFlingVelocity,
        frictionMultiplier = decayFriction,
        onVelocityRecorded = { vel ->
            lastRecordedFlingVelocity = vel
            flingCount++
        }
    )

    RouteScaffold(
        title = "Sandbox Scroll & Fling",
        onNavigateBack = onNavigateBack,
        snackbarHostState = snackbarHostState,
        actions = {
            IconButton(onClick = { isSettingsPanelOpen = !isSettingsPanelOpen }) {
                Icon(
                    imageVector = if (isSettingsPanelOpen) Icons.Rounded.Tune else Icons.Rounded.Settings,
                    contentDescription = "Réglages",
                    tint = if (isSettingsPanelOpen) BlazeOrange else TextPrimary
                )
            }
        }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Interactive Calibration Panel
            AnimatedVisibility(visible = isSettingsPanelOpen) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = ElevatedGraphite)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "🎛️ Calibrage du Scroll",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            // Live indicator badge
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (abs(lastRecordedFlingVelocity) > 10f) BlazeOrange.copy(alpha = 0.2f) else DarkGraphite
                            ) {
                                Text(
                                    text = "Dernier Fling: ${lastRecordedFlingVelocity.roundToInt()} px/s",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (abs(lastRecordedFlingVelocity) > 10f) BlazeOrange else TextSecondary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        // 1. Max Fling Velocity Slider
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Vitesse Max Fling :", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                                Text("${maxFlingVelocity.roundToInt()} px/s", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = BlazeOrange)
                            }
                            Slider(
                                value = maxFlingVelocity,
                                onValueChange = { maxFlingVelocity = it },
                                valueRange = 500f..12000f,
                                steps = 22,
                                colors = SliderDefaults.colors(thumbColor = BlazeOrange, activeTrackColor = BlazeOrange)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                PresetChip("Très doux (1200)", maxFlingVelocity == 1200f) { maxFlingVelocity = 1200f }
                                PresetChip("Modéré (2500)", maxFlingVelocity == 2500f) { maxFlingVelocity = 2500f }
                                PresetChip("Défaut (8000)", maxFlingVelocity == 8000f) { maxFlingVelocity = 8000f }
                            }
                        }

                        // 2. Drag Damping Slider
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Résistance au doigt (Damping) :", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                                Text("${(scrollDamping * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = BlazeOrange)
                            }
                            Slider(
                                value = scrollDamping,
                                onValueChange = { scrollDamping = it },
                                valueRange = 0.3f..1.0f,
                                steps = 7,
                                colors = SliderDefaults.colors(thumbColor = BlazeOrange, activeTrackColor = BlazeOrange)
                            )
                        }

                        // 3. Decay Friction Slider
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Freinage / Décélération :", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                                Text("${String.format("%.1f", decayFriction)}x", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = BlazeOrange)
                            }
                            Slider(
                                value = decayFriction,
                                onValueChange = { decayFriction = it },
                                valueRange = 0.5f..3.0f,
                                steps = 10,
                                colors = SliderDefaults.colors(thumbColor = BlazeOrange, activeTrackColor = BlazeOrange)
                            )
                        }

                        // 4. Source selector
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilterChip(
                                selected = itemSourceMode == 0,
                                onClick = { itemSourceMode = 0 },
                                label = { Text("Favoris (${likedTracksState.value?.size ?: 0})") }
                            )
                            FilterChip(
                                selected = itemSourceMode == 1,
                                onClick = { itemSourceMode = 1 },
                                label = { Text("100 pistes") }
                            )
                            FilterChip(
                                selected = itemSourceMode == 2,
                                onClick = { itemSourceMode = 2 },
                                label = { Text("300 pistes") }
                            )
                        }
                    }
                }
            }

            // Favorites Replica List with applied Fling & Damping
            if (likedTracksState.value == null && allTracksState.value == null) {
                ShimmerTrackList(count = 6)
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .applyScrollDamping(scrollDamping),
                    flingBehavior = customFlingBehavior,
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    // Header mimicking FavoritesScreen
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .background(
                                    Brush.linearGradient(listOf(Color(0xFFFF6B00), Color(0xFF1A0A00))),
                                    RoundedCornerShape(24.dp),
                                )
                                .padding(20.dp),
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(
                                    Icons.Rounded.Favorite,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(32.dp),
                                )
                                Text(
                                    "Favoris (Sandbox Test)",
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                )
                                Text(
                                    "${displayTracks.size} piste(s) — Vitesse max: ${maxFlingVelocity.roundToInt()} px/s",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White.copy(alpha = 0.75f),
                                )
                            }
                        }
                    }

                    // Action buttons (Play / Shuffle)
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Button(
                                onClick = {
                                    if (displayTracks.isNotEmpty() && playerViewModel != null) {
                                        playerViewModel.onEvent(
                                            PlayerEvent.PlayTrack(
                                                trackId = displayTracks.first().id,
                                                contextType = "favorites",
                                                contextId = "favorites",
                                                contextTracks = displayTracks.map { it.toQueuedTrack() },
                                                startIndex = 0,
                                            ),
                                        )
                                    }
                                },
                                enabled = displayTracks.isNotEmpty(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = BlazeOrange,
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(vertical = 10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Play", fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = {
                                    if (displayTracks.isNotEmpty() && playerViewModel != null) {
                                        val shuffled = displayTracks.shuffled()
                                        playerViewModel.onEvent(
                                            PlayerEvent.PlayTrack(
                                                trackId = shuffled.first().id,
                                                contextType = "favorites",
                                                contextId = "favorites",
                                                contextTracks = shuffled.map { it.toQueuedTrack() },
                                                startIndex = 0,
                                            ),
                                        )
                                    }
                                },
                                enabled = displayTracks.isNotEmpty(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = DarkGraphite,
                                    contentColor = TextPrimary
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(vertical = 10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Shuffle,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Shuffle", fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    // Track rows
                    itemsIndexed(
                        items = displayTracks,
                        key = { index, track -> "${track.id}_$index" }
                    ) { index, track ->
                        val currentOnClick = remember(track.id) {
                            {
                                if (playerViewModel != null) {
                                    playerViewModel.onEvent(
                                        PlayerEvent.PlayTrack(
                                            trackId = track.id,
                                            contextType = "favorites",
                                            contextId = "favorites",
                                            contextTracks = displayTracks.map { it.toQueuedTrack() },
                                            startIndex = index,
                                        ),
                                    )
                                }
                            }
                        }

                        val onLikeClick = remember(track.id, track.isLiked) {
                            {
                                scope.launch {
                                    repository.toggleLike(track.id, currentlyLiked = track.isLiked, contextType = "favorites")
                                }
                                Unit
                            }
                        }

                        SharedTrackRowItem(
                            title = track.title,
                            subtitle = listOfNotNull(track.artistName, track.albumTitle).joinToString(" • "),
                            coverUri = if (showCoverImages) track.coverUri else null,
                            onClick = currentOnClick,
                            showCover = true,
                            contextType = "favorites",
                            isLiked = track.isLiked,
                            onLike = onLikeClick,
                            onUnlike = onLikeClick,
                            onAddToQueue = {
                                playerViewModel?.onEvent(PlayerEvent.AddToQueue(track.toQueuedTrack()))
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetChip(text: String, isSelected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected) BlazeOrange else DarkGraphite,
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (isSelected) Color.White else TextSecondary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun rememberCappedFlingBehavior(
    maxVelocity: Float,
    frictionMultiplier: Float = 1.0f,
    onVelocityRecorded: (Float) -> Unit = {}
): FlingBehavior {
    val decayAnimationSpec = remember(frictionMultiplier) {
        exponentialDecay<Float>(frictionMultiplier = frictionMultiplier)
    }
    return remember(maxVelocity, decayAnimationSpec) {
        object : FlingBehavior {
            override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
                val clampedVelocity = initialVelocity.coerceIn(-maxVelocity, maxVelocity)
                onVelocityRecorded(clampedVelocity)

                if (abs(clampedVelocity) < 1f) {
                    return 0f
                }

                var lastValue = 0f
                var remainingVelocity = clampedVelocity

                AnimationState(
                    initialValue = 0f,
                    initialVelocity = clampedVelocity
                ).animateDecay(decayAnimationSpec) {
                    val delta = value - lastValue
                    val consumed = scrollBy(delta)
                    lastValue = value
                    remainingVelocity = velocity
                    if (abs(delta - consumed) > 0.5f) {
                        cancelAnimation()
                    }
                }
                return remainingVelocity
            }
        }
    }
}

private fun Modifier.applyScrollDamping(damping: Float): Modifier {
    if (damping >= 0.99f) return this
    return this.nestedScroll(object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            return if (source == NestedScrollSource.UserInput) {
                available * (1f - damping)
            } else {
                Offset.Zero
            }
        }
    })
}
