package com.example.gpspressurelogger.ui.navigation

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.gpspressurelogger.ui.screens.HomeScreen
import com.example.gpspressurelogger.ui.screens.MapScreen
import com.example.gpspressurelogger.ui.screens.SettingsScreen

sealed class Screen(val route: String) {
    object Home     : Screen("home")
    object Map      : Screen("map")
    object Settings : Screen("settings")
}

/**
 * @param startDestination ウィジェットからのディープリンク等で起動時の画面を指定できる
 */
@Composable
fun AppNavGraph(
    startDestination: String = Screen.Home.route,
    requestedRoute: String = startDestination,
    requestId: Long = 0L,
    graphResetRequestId: Long = 0L
) {
    val navController = rememberNavController()

    LaunchedEffect(requestId) {
        if (requestId <= 1L) return@LaunchedEffect
        navController.navigate(requestedRoute) {
            launchSingleTop = true
            restoreState = true
        }
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Screen.Home.route) {
            HomeScreen(
                onNavigateToMap      = { navController.navigate(Screen.Map.route) },
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                graphResetRequestId = graphResetRequestId
            )
        }
        composable(Screen.Map.route) {
            MapScreen(onBack = { navController.popBackStack() })
        }
        composable(Screen.Settings.route) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
