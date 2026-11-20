@file:OptIn(ExperimentalMaterial3Api::class)

package com.androx.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.androx.app.ui.components.ProvideHaptics
import com.androx.app.ui.components.rememberIsWide
import com.androx.app.ui.editor.EditorScreen
import com.androx.app.ui.editor.EditorViewModel
import com.androx.app.ui.history.HistoryScreen
import com.androx.app.ui.home.HomeScreen
import com.androx.app.ui.settings.SettingsScreen
import com.androx.app.ui.templates.TemplatesScreen
import com.androx.app.ui.variables.VariablesScreen

private enum class Tab(val route: String, val label: String) {
    HOME("home", "Home"),
    AUTOMATIONS("automations", "Automations"),
    HISTORY("history", "History"),
    SETTINGS("settings", "Settings"),
}

private const val EDITOR_ROUTE = "editor?id={id}&kind={kind}"
private const val TEMPLATES_ROUTE = "templates"
private const val VARIABLES_ROUTE = "variables"

fun editorRoute(id: String?, kind: EditorKind): String =
    if (id == null) "editor?kind=${kind.name}" else "editor?id=$id&kind=${kind.name}"

@Composable
fun AndroxRoot(container: AppContainer) {
    val navController = rememberNavController()
    val mainVm: MainViewModel = viewModel(factory = viewModelFactory { initializer { MainViewModel(container) } })
    val snackbar = remember { SnackbarHostState() }
    val settings by mainVm.settings.collectAsStateWithLifecycle()
    val unseenFailures by mainVm.unseenFailures.collectAsStateWithLifecycle()
    val history by mainVm.history.collectAsStateWithLifecycle()
    val focusRun by mainVm.focusRunId.collectAsStateWithLifecycle()

    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val showBar = Tab.values().any { it.route == route }

    fun openTab(tab: Tab) {
        navController.navigate(tab.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun openEditor(id: String?, kind: EditorKind) = navController.navigate(editorRoute(id, kind))

    LaunchedEffect(Unit) {
        mainVm.messages.collect { message ->
            val result = snackbar.showSnackbar(
                message = message.text,
                actionLabel = message.actionLabel,
                withDismissAction = message.actionLabel == null,
                duration = if (message.actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                when (val action = message.action) {
                    is MessageAction.Invoke -> action.block()
                    is MessageAction.OpenRun -> mainVm.focusRun(action.id)
                    is MessageAction.OpenEditor -> openEditor(action.id, action.kind)
                    null -> Unit
                }
            }
        }
    }

    // A snackbar's "Details" button asks History to show one run.
    LaunchedEffect(focusRun) {
        if (focusRun != null && route != Tab.HISTORY.route) openTab(Tab.HISTORY)
    }
    // Opening History clears the failure badge.
    LaunchedEffect(route, history.size) {
        if (route == Tab.HISTORY.route) mainVm.markHistorySeen()
    }

    val wide = rememberIsWide()

    ProvideHaptics(settings.haptics) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                AnimatedVisibility(
                    visible = showBar && !wide,
                    enter = fadeIn(tween(160)) + expandVertically(tween(200)),
                    exit = fadeOut(tween(120)) + shrinkVertically(tween(160)),
                ) {
                    NavigationBar {
                        Tab.values().forEach { tab ->
                            NavigationBarItem(
                                selected = route == tab.route,
                                onClick = { openTab(tab) },
                                icon = { TabIcon(tab, route == tab.route, unseenFailures) },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
                if (showBar && wide) {
                    NavigationRail {
                        Tab.values().forEach { tab ->
                            NavigationRailItem(
                                selected = route == tab.route,
                                onClick = { openTab(tab) },
                                icon = { TabIcon(tab, route == tab.route, unseenFailures) },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
                NavHost(
                    navController = navController,
                    startDestination = Tab.HOME.route,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    // Material "fade through": the old screen fades out, then the new one fades in and settles.
                    enterTransition = { fadeIn(tween(210, delayMillis = 90)) + scaleIn(initialScale = 0.92f, animationSpec = tween(210, delayMillis = 90)) },
                    exitTransition = { fadeOut(tween(90)) },
                    popEnterTransition = { fadeIn(tween(210, delayMillis = 90)) + scaleIn(initialScale = 0.92f, animationSpec = tween(210, delayMillis = 90)) },
                    popExitTransition = { fadeOut(tween(90)) },
                ) {
                    composable(Tab.HOME.route) {
                        HomeScreen(
                            vm = mainVm,
                            onCreate = { kind -> openEditor(null, kind) },
                            onEdit = { automation -> openEditor(automation.id, automation.editor) },
                            onOpenAutomations = { openTab(Tab.AUTOMATIONS) },
                            onOpenHistory = { openTab(Tab.HISTORY) },
                            onOpenSettings = { openTab(Tab.SETTINGS) },
                            onOpenTemplates = { navController.navigate(TEMPLATES_ROUTE) },
                        )
                    }
                    composable(Tab.AUTOMATIONS.route) {
                        AutomationsScreen(
                            vm = mainVm,
                            onCreate = { kind -> openEditor(null, kind) },
                            onEdit = { automation -> openEditor(automation.id, automation.editor) },
                            onOpenTemplates = { navController.navigate(TEMPLATES_ROUTE) },
                        )
                    }
                    composable(Tab.HISTORY.route) { HistoryScreen(vm = mainVm) }
                    composable(Tab.SETTINGS.route) {
                        SettingsScreen(vm = mainVm, onOpenVariables = { navController.navigate(VARIABLES_ROUTE) })
                    }
                    composable(
                        TEMPLATES_ROUTE,
                        enterTransition = { slideInHorizontally(tween(260)) { it / 6 } + fadeIn(tween(220)) },
                        exitTransition = { fadeOut(tween(120)) },
                        popEnterTransition = { fadeIn(tween(200)) },
                        popExitTransition = { slideOutHorizontally(tween(200)) { it / 6 } + fadeOut(tween(160)) },
                    ) { TemplatesScreen(vm = mainVm, onBack = { navController.popBackStack() }) }
                    composable(
                        VARIABLES_ROUTE,
                        enterTransition = { slideInHorizontally(tween(260)) { it / 6 } + fadeIn(tween(220)) },
                        exitTransition = { fadeOut(tween(120)) },
                        popEnterTransition = { fadeIn(tween(200)) },
                        popExitTransition = { slideOutHorizontally(tween(200)) { it / 6 } + fadeOut(tween(160)) },
                    ) { VariablesScreen(vm = mainVm, onBack = { navController.popBackStack() }) }
                    composable(
                        route = EDITOR_ROUTE,
                        arguments = listOf(
                            navArgument("id") { type = NavType.StringType; defaultValue = "" },
                            navArgument("kind") { type = NavType.StringType; defaultValue = EditorKind.BUILDER.name },
                        ),
                        enterTransition = { slideInVertically(tween(260)) { it / 10 } + fadeIn(tween(220)) },
                        exitTransition = { fadeOut(tween(120)) },
                        popEnterTransition = { fadeIn(tween(200)) },
                        popExitTransition = { slideOutVertically(tween(200)) { it / 10 } + fadeOut(tween(160)) },
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
        }
    }
}

@Composable
private fun TabIcon(tab: Tab, selected: Boolean, unseenFailures: Int) {
    val painter = tabPainter(tab, selected)
    if (tab == Tab.HISTORY && unseenFailures > 0) {
        BadgedBox(badge = { Badge { Text(if (unseenFailures > 9) "9+" else unseenFailures.toString()) } }) {
            Icon(painter, contentDescription = null)
        }
    } else {
        Icon(painter, contentDescription = null)
    }
}

@Composable
private fun tabPainter(tab: Tab, selected: Boolean): Painter = when (tab) {
    Tab.HOME -> rememberVectorPainter(if (selected) Icons.Filled.Home else Icons.Outlined.Home)
    Tab.AUTOMATIONS -> painterResource(R.drawable.ic_bolt)
    Tab.HISTORY -> painterResource(R.drawable.ic_history)
    Tab.SETTINGS -> rememberVectorPainter(if (selected) Icons.Filled.Settings else Icons.Outlined.Settings)
}
