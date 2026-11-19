@file:OptIn(ExperimentalMaterial3Api::class)

package com.androx.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.androx.app.AppContainer
import com.androx.app.R
import com.androx.app.model.EditorKind
import com.androx.app.ui.automations.AutomationsScreen
import com.androx.app.ui.components.NewAutomationSheet
import com.androx.app.ui.editor.EditorScreen
import com.androx.app.ui.editor.EditorViewModel
import com.androx.app.ui.history.HistoryScreen
import com.androx.app.ui.home.HomeScreen
import com.androx.app.ui.settings.SettingsScreen

private enum class Tab(val route: String, val label: String) {
    HOME("home", "Home"),
    AUTOMATIONS("automations", "Automations"),
    HISTORY("history", "History"),
    SETTINGS("settings", "Settings"),
}

private const val EDITOR_ROUTE = "editor?id={id}&kind={kind}"

fun editorRoute(id: String?, kind: EditorKind): String =
    if (id == null) "editor?kind=${kind.name}" else "editor?id=$id&kind=${kind.name}"

/**
 * One top-level Scaffold owns the bottom navigation bar, the FAB and the snackbar, so they stack and animate the way
 * the Material 3 spec describes. Each destination only supplies its own top app bar and content.
 */
@Composable
fun AndroxRoot(container: AppContainer) {
    val navController = rememberNavController()
    val mainVm: MainViewModel = viewModel(factory = viewModelFactory { initializer { MainViewModel(container) } })
    val snackbar = remember { SnackbarHostState() }
    var showNewSheet by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        mainVm.messages.collect { snackbar.showSnackbar(it) }
    }

    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val showBar = Tab.values().any { it.route == route }
    val showFab = route == Tab.HOME.route || route == Tab.AUTOMATIONS.route

    fun openTab(tab: Tab) {
        navController.navigate(tab.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun openEditor(id: String?, kind: EditorKind) = navController.navigate(editorRoute(id, kind))

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            AnimatedVisibility(
                visible = showFab,
                enter = scaleIn(tween(180)) + fadeIn(tween(180)),
                exit = scaleOut(tween(120)) + fadeOut(tween(120)),
            ) {
                FloatingActionButton(onClick = { showNewSheet = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "New automation")
                }
            }
        },
        bottomBar = {
            AnimatedVisibility(
                visible = showBar,
                enter = fadeIn(tween(160)) + expandVertically(tween(200)),
                exit = fadeOut(tween(120)) + shrinkVertically(tween(160)),
            ) {
                NavigationBar {
                    Tab.values().forEach { tab ->
                        val selected = route == tab.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = { openTab(tab) },
                            icon = { Icon(tabIcon(tab, selected), contentDescription = null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Tab.HOME.route,
            modifier = Modifier.padding(padding),
            // Top-level destinations use Material's "fade through"; the editor uses a horizontal shared-axis slide.
            enterTransition = { fadeIn(tween(210, delayMillis = 90)) },
            exitTransition = { fadeOut(tween(90)) },
            popEnterTransition = { fadeIn(tween(210, delayMillis = 90)) },
            popExitTransition = { fadeOut(tween(90)) },
        ) {
            composable(Tab.HOME.route) {
                HomeScreen(
                    vm = mainVm,
                    onNew = { showNewSheet = true },
                    onOpenAutomations = { openTab(Tab.AUTOMATIONS) },
                    onOpenHistory = { openTab(Tab.HISTORY) },
                )
            }
            composable(Tab.AUTOMATIONS.route) {
                AutomationsScreen(
                    vm = mainVm,
                    onNew = { showNewSheet = true },
                    onEdit = { automation -> openEditor(automation.id, automation.editor) },
                )
            }
            composable(Tab.HISTORY.route) { HistoryScreen(vm = mainVm) }
            composable(Tab.SETTINGS.route) { SettingsScreen(vm = mainVm) }
            composable(
                route = EDITOR_ROUTE,
                arguments = listOf(
                    navArgument("id") { type = NavType.StringType; defaultValue = "" },
                    navArgument("kind") { type = NavType.StringType; defaultValue = EditorKind.BUILDER.name },
                ),
                enterTransition = { slideInHorizontally(tween(300)) { it / 5 } + fadeIn(tween(300)) },
                exitTransition = { fadeOut(tween(90)) },
                popEnterTransition = { fadeIn(tween(210, delayMillis = 90)) },
                popExitTransition = { slideOutHorizontally(tween(250)) { it / 5 } + fadeOut(tween(200)) },
            ) { entry ->
                val id = entry.arguments?.getString("id").orEmpty().ifBlank { null }
                val kind = runCatching { EditorKind.valueOf(entry.arguments?.getString("kind").orEmpty()) }
                    .getOrDefault(EditorKind.BUILDER)
                val editorVm: EditorViewModel = viewModel(
                    factory = viewModelFactory { initializer { EditorViewModel(container, id, kind) } }
                )
                EditorScreen(vm = editorVm, onClose = { navController.popBackStack() })
            }
        }
    }

    if (showNewSheet) {
        NewAutomationSheet(
            onPick = { kind -> showNewSheet = false; openEditor(null, kind) },
            onDismiss = { showNewSheet = false },
        )
    }
}

/** Filled glyph when selected, outlined when not: the system navigation-bar convention. */
@Composable
private fun tabIcon(tab: Tab, selected: Boolean): Painter = when (tab) {
    Tab.HOME -> rememberVectorPainter(if (selected) Icons.Filled.Home else Icons.Outlined.Home)
    Tab.AUTOMATIONS -> painterResource(R.drawable.ic_bolt)
    Tab.HISTORY -> painterResource(R.drawable.ic_history)
    Tab.SETTINGS -> rememberVectorPainter(if (selected) Icons.Filled.Settings else Icons.Outlined.Settings)
}
