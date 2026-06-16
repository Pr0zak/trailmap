package com.trailmap

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.trailmap.ui.MapScreen
import com.trailmap.ui.TrailDetailScreen
import com.trailmap.ui.TrailListScreen
import com.trailmap.ui.TrailsViewModel
import com.trailmap.ui.theme.TrailmapTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TrailmapTheme { TrailmapRoot() } }
    }
}

private sealed class Tab(val route: String, val label: String) {
    data object Map : Tab("map", "Map")
    data object List : Tab("list", "Trails")
}

@Composable
private fun TrailmapRoot() {
    val nav = rememberNavController()
    val vm: TrailsViewModel = viewModel()
    val tabs = listOf(Tab.Map, Tab.List)
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold(
        bottomBar = {
            // Hide the bar on the detail screen for an immersive read.
            if (currentRoute == Tab.Map.route || currentRoute == Tab.List.route) {
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
                                    if (tab is Tab.Map) Icons.Filled.Map else Icons.AutoMirrored.Filled.List,
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
                MapScreen(vm) { id -> nav.navigate("detail/$id") }
            }
            composable(Tab.List.route) {
                TrailListScreen(vm) { id -> nav.navigate("detail/$id") }
            }
            composable("detail/{id}") { entry ->
                val id = entry.arguments?.getString("id").orEmpty()
                TrailDetailScreen(vm, id, onBack = { nav.popBackStack() })
            }
        }
    }
}
