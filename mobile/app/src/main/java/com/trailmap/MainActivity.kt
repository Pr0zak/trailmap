package com.trailmap

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Route
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trailmap.data.DiagLog
import com.trailmap.update.UpdateChecker
import com.trailmap.update.UpdateInfo
import kotlinx.coroutines.launch
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.trailmap.ui.DiagnosticsScreen
import com.trailmap.ui.MapScreen
import com.trailmap.ui.OfflineScreen
import com.trailmap.ui.RideDetailScreen
import com.trailmap.ui.RidesScreen
import com.trailmap.ui.TrailDetailScreen
import com.trailmap.ui.TrailListScreen
import com.trailmap.ui.TrailsViewModel
import com.trailmap.ui.MapTheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trailmap.ui.theme.TrailmapTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagLog.log("app", "activity created (restored=${savedInstanceState != null})")
        setContent {
            val vm: TrailsViewModel = viewModel()
            val ui by vm.state.collectAsStateWithLifecycle()
            // The theme choice on the map's Layers menu applies to the whole app, so a forced
            // dark map doesn't sit under light cards and bars.
            val dark = when (ui.mapTheme) {
                MapTheme.SYSTEM -> isSystemInDarkTheme()
                MapTheme.LIGHT -> false
                MapTheme.DARK -> true
            }
            TrailmapTheme(darkTheme = dark) { TrailmapRoot(vm) }
        }
    }
}

private sealed class Tab(val route: String, val label: String) {
    data object Map : Tab("map", "Map")
    data object List : Tab("list", "Trails")
    data object Rides : Tab("rides", "Rides")
}

@Composable
private fun TrailmapRoot(vm: TrailsViewModel) {
    val nav = rememberNavController()
    val tabs = listOf(Tab.Map, Tab.List, Tab.Rides)
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold(
        bottomBar = {
            // Bar shows on top-level tabs; hidden on detail/ride/offline for an immersive read.
            if (currentRoute in tabs.map { it.route }) {
                NavigationBar {
                    tabs.forEach { tab ->
                        val selected = backStack?.destination?.hierarchy?.any { it.route == tab.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(Tab.Map.route) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    when (tab) {
                                        Tab.Map -> Icons.Filled.Map
                                        Tab.List -> Icons.AutoMirrored.Filled.List
                                        Tab.Rides -> Icons.Filled.Route
                                    },
                                    contentDescription = tab.label,
                                )
                            },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Tab.Map.route, modifier = Modifier.padding(padding)) {
            composable(Tab.Map.route) {
                MapScreen(
                    vm,
                    onOpenTrail = { id -> nav.navigate("detail/$id") },
                    onOpenOffline = { nav.navigate("offline") },
                )
            }
            composable(Tab.List.route) {
                TrailListScreen(
                    vm,
                    onOpenTrail = { id -> nav.navigate("detail/$id") },
                    onShowOnMap = {
                        nav.navigate(Tab.Map.route) {
                            popUpTo(Tab.Map.route) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
            composable(Tab.Rides.route) {
                RidesScreen(vm, onOpenRide = { id -> nav.navigate("ride/$id") })
            }
            composable("ride/{id}") { entry ->
                val id = entry.arguments?.getString("id").orEmpty()
                RideDetailScreen(vm, id, onBack = { nav.popBackStack() }, onOpenTrail = { tid -> nav.navigate("detail/$tid") })
            }
            composable("offline") {
                OfflineScreen(
                    vm,
                    onBack = { nav.popBackStack() },
                    onOpenDiagnostics = { nav.navigate("diagnostics") },
                )
            }
            composable("diagnostics") {
                DiagnosticsScreen(onBack = { nav.popBackStack() })
            }
            composable("detail/{id}") { entry ->
                val id = entry.arguments?.getString("id").orEmpty()
                TrailDetailScreen(
                    vm, id,
                    onBack = { nav.popBackStack() },
                    onShowOnMap = {
                        nav.navigate(Tab.Map.route) {
                            popUpTo(Tab.Map.route) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        }
    }

    UpdateGate()
}

/** On launch, polls GitHub Releases; if a newer APK exists, offers to download + install it. */
@Composable
private fun UpdateGate() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { update = UpdateChecker.check() }

    val info = update ?: return
    AlertDialog(
        onDismissRequest = { if (!busy) update = null },
        title = { Text("Update available") },
        text = {
            Column {
                Text("trailmap ${info.version} is available — you have ${UpdateChecker.currentVersion()}.")
                // The release body is fetched with the version check; show what changed.
                val notes = info.notes?.trim().orEmpty()
                if (notes.isNotEmpty()) {
                    Text(
                        "What's new",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                    )
                    Text(
                        notes,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val apk = UpdateChecker.downloadApk(context, info.apkUrl)
                            UpdateChecker.install(context, apk)
                        } catch (e: Exception) {
                            Toast.makeText(context, "Update failed: ${e.message}", Toast.LENGTH_LONG).show()
                        } finally {
                            busy = false
                            update = null
                        }
                    }
                },
            ) { Text(if (busy) "Downloading…" else "Download & install") }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = { update = null }) { Text("Later") }
        },
    )
}
