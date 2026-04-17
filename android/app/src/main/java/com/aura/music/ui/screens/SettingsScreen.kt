package com.aura.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aura.music.data.local.UserSettingsEntity
import com.aura.music.data.repository.LibraryDashboardSummary
import com.aura.music.data.repository.LocalLibraryRepository
import com.aura.music.ui.RouteScaffold
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    repository: LocalLibraryRepository,
    onNavigateBack: () -> Unit,
) {
    var refreshTick by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val settingsState = produceState<UserSettingsEntity?>(initialValue = null, repository, refreshTick) {
        repository.ensureDefaults()
        value = repository.getSettings()
    }
    val summaryState = produceState<LibraryDashboardSummary?>(initialValue = null, repository, refreshTick) {
        value = repository.getLibraryDashboardSummary()
    }
    val settings = settingsState.value

    RouteScaffold(title = "Paramètres", onNavigateBack = onNavigateBack) {
        if (settings == null) {
            EmptyStateSurface("Settings indisponibles", "Le profil local n'est pas encore initialise.")
            return@RouteScaffold
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                HeroIdentityCard(
                    title = "Preferences AURA",
                    subtitle = "Recherche online, sync future et diagnostic local.",
                    gradient = Brush.linearGradient(listOf(Color(0xFF232323), Color(0xFF050505))),
                )
            }
            item {
                SettingsCard(title = "Compte et sync") {
                    SettingToggleRow(
                        title = "Sync cloud",
                        subtitle = "Optionnelle. L'app reste utile sans backend cloud.",
                        checked = settings.syncEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                repository.setSyncEnabled(enabled)
                                refreshTick++
                            }
                        },
                    )
                    Divider()
                    PolicyRow(
                        title = "Sync stats",
                        selected = settings.statsSyncNetworkPolicy,
                        options = listOf("wifi_only", "any_network"),
                        onSelect = { policy ->
                            scope.launch {
                                repository.setStatsSyncNetworkPolicy(policy)
                                refreshTick++
                            }
                        },
                    )
                }
            }
            item {
                SettingsCard(title = "Recherche") {
                    SettingToggleRow(
                        title = "Recherche online",
                        subtitle = "Active la partie backend-only pour la recherche enrichie.",
                        checked = settings.onlineSearchEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                repository.setOnlineSearchEnabled(enabled)
                                refreshTick++
                            }
                        },
                    )
                    Divider()
                    PolicyRow(
                        title = "Politique reseau",
                        selected = settings.onlineSearchNetworkPolicy,
                        options = listOf("wifi_only", "any_network"),
                        onSelect = { policy ->
                            scope.launch {
                                repository.setOnlineSearchNetworkPolicy(policy)
                                refreshTick++
                            }
                        },
                    )
                }
            }
            item {
                SettingsCard(title = "Diagnostics") {
                    Text("Pistes indexees: ${summaryState.value?.roomTrackCount ?: 0}", style = MaterialTheme.typography.bodyMedium)
                    Text("MediaStore detecte: ${summaryState.value?.mediaStoreTrackCount ?: 0}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Snapshot actif: ${if (summaryState.value?.activeSnapshot != null) "oui" else "non"}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PolicyRow(
    title: String,
    selected: String,
    options: List<String>,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                val icon = if (option == "wifi_only") Icons.Rounded.Wifi else Icons.Rounded.Sync
                Card(modifier = Modifier.clickable { onSelect(option) }, shape = RoundedCornerShape(999.dp)) {
                    Row(
                        modifier = Modifier
                            .background(if (selected == option) Color(0xFFFF6B00) else MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(icon, contentDescription = null, tint = if (selected == option) Color(0xFF160A00) else MaterialTheme.colorScheme.onSurface)
                        Text(option.replace('_', ' '), color = if (selected == option) Color(0xFF160A00) else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}
