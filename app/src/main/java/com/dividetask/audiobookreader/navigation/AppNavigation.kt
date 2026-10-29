package com.dividetask.audiobookreader.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.dividetask.audiobookreader.debug.AppDebugLog
import com.dividetask.audiobookreader.ui.audible.AudibleLoginScreen
import com.dividetask.audiobookreader.ui.audible.AudibleViewModel
import com.dividetask.audiobookreader.ui.audible.WebsiteDownloadScreen
import com.dividetask.audiobookreader.ui.common.MiniPlayerBar
import com.dividetask.audiobookreader.ui.common.MiniPlayerViewModel
import com.dividetask.audiobookreader.ui.main.MainScreen
import com.dividetask.audiobookreader.ui.details.BookDetailsScreen
import com.dividetask.audiobookreader.ui.player.PlayerScreen
import com.dividetask.audiobookreader.ui.settings.DebugLogScreen
import com.dividetask.audiobookreader.ui.settings.SettingsScreen

@Composable
fun AppNavigation(debugLog: AppDebugLog) {
    val navController = rememberNavController()
    val miniPlayer: MiniPlayerViewModel = hiltViewModel()
    val playbackState by miniPlayer.playbackState.collectAsState()
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    // Playback controls on every screen except the full player
    val showMiniPlayer = playbackState.title.isNotBlank() && currentRoute?.startsWith("player/") != true

    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .weight(1f)
                // The bar handles the navigation-bar inset, so screens above it shouldn't pad for it too
                .then(if (showMiniPlayer) Modifier.consumeWindowInsets(WindowInsets.navigationBars) else Modifier)
        ) {
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
                        },
                        onWebsiteDownload = { asin ->
                            navController.navigate("website_download/$asin")
                        }
                    )
                }
                composable(
                    "website_download/{asin}",
                    arguments = listOf(navArgument("asin") { type = NavType.StringType })
                ) {
                    WebsiteDownloadScreen(onDone = { navController.popBackStack() })
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
                        onBack = { navController.popBackStack() },
                        onPage = { url, status ->
                            viewModel.requestLog.request("webview-signin", "GET", url, status, null,
                                note = if (status == null) "page" else "page error")
                        }
                    )
                }
            }
        }
        if (showMiniPlayer) {
            MiniPlayerBar(
                viewModel = miniPlayer,
                onOpenPlayer = { id -> navController.navigate("player/$id") }
            )
        }
    }
}
