package com.dividetask.audiobookreader.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.dividetask.audiobookreader.debug.AppDebugLog
import com.dividetask.audiobookreader.ui.audible.AudibleLoginScreen
import com.dividetask.audiobookreader.ui.audible.AudibleViewModel
import com.dividetask.audiobookreader.ui.main.MainScreen
import com.dividetask.audiobookreader.ui.details.BookDetailsScreen
import com.dividetask.audiobookreader.ui.player.PlayerScreen
import com.dividetask.audiobookreader.ui.settings.DebugLogScreen
import com.dividetask.audiobookreader.ui.settings.SettingsScreen

@Composable
fun AppNavigation(debugLog: AppDebugLog) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "main") {
        composable("main") {
            MainScreen(
                onBookClick = { bookId ->
                    navController.navigate("player/$bookId")
                },
                onAudibleLogin = {
                    navController.navigate("audible_login")
                },
                onSettingsClick = {
                    navController.navigate("settings")
                }
            )
        }
        composable(
            "player/{bookId}",
            arguments = listOf(navArgument("bookId") { type = NavType.LongType })
        ) { backStackEntry ->
            val bookId = backStackEntry.arguments?.getLong("bookId") ?: return@composable
            PlayerScreen(
                bookId = bookId,
                onBack = { navController.popBackStack() },
                onDetails = { asin -> navController.navigate("details/$asin") }
            )
        }
        composable(
            "details/{asin}",
            arguments = listOf(navArgument("asin") { type = NavType.StringType })
        ) {
            BookDetailsScreen(
                onBack = { navController.popBackStack() },
                onPlay = { id ->
                    // Go back to the player if it's already open for this book
                    if (!navController.popBackStack("player/$id", inclusive = false)) {
                        navController.navigate("player/$id")
                    }
                },
                onOpenBook = { asin -> navController.navigate("details/$asin") }
            )
        }
        composable("settings") {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onAudibleLogin = { navController.navigate("audible_login") },
                onDebugLog = { navController.navigate("debug_log") }
            )
        }
        composable("debug_log") {
            DebugLogScreen(
                onBack = { navController.popBackStack() },
                debugLog = debugLog
            )
        }
        composable("audible_login") {
            val viewModel: AudibleViewModel = hiltViewModel()
            val serial = remember { viewModel.generateSerial() }
            val codeVerifier = remember { viewModel.generateCodeVerifier() }

            AudibleLoginScreen(
                serial = serial,
                codeVerifier = codeVerifier,
                onLoginComplete = { credentials ->
                    viewModel.onLoginComplete(credentials)
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() }
            )
        }
    }
}
