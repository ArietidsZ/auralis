package com.dialect.interpreter.ui.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.dialect.interpreter.DialectApp
import com.dialect.interpreter.ui.screens.*

/**
 * App navigation graph with shared-axis transitions.
 */
object AppRoutes {
    const val MODEL_DOWNLOAD = "model_download"
    const val INTERPRET = "interpret"
    const val VOICE_PROFILE = "voice_profile"
    const val SETTINGS = "settings"
}

private const val TRANSITION_MS = 350

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val appContext = LocalContext.current.applicationContext as DialectApp
    val modelManager = appContext.container.modelManager
    val modelRepo = remember { appContext.container.modelRepository }

    val startDestination = if (modelRepo.areModelsReady()) {
        AppRoutes.INTERPRET
    } else {
        AppRoutes.MODEL_DOWNLOAD
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = {
            slideInHorizontally(
                initialOffsetX = { it / 4 },
                animationSpec = tween(TRANSITION_MS)
            ) + fadeIn(tween(TRANSITION_MS))
        },
        exitTransition = {
            slideOutHorizontally(
                targetOffsetX = { -it / 4 },
                animationSpec = tween(TRANSITION_MS)
            ) + fadeOut(tween(TRANSITION_MS / 2))
        },
        popEnterTransition = {
            slideInHorizontally(
                initialOffsetX = { -it / 4 },
                animationSpec = tween(TRANSITION_MS)
            ) + fadeIn(tween(TRANSITION_MS))
        },
        popExitTransition = {
            slideOutHorizontally(
                targetOffsetX = { it / 4 },
                animationSpec = tween(TRANSITION_MS)
            ) + fadeOut(tween(TRANSITION_MS / 2))
        }
    ) {
        composable(AppRoutes.MODEL_DOWNLOAD) {
            ModelDownloadScreen(
                modelRepository = modelRepo,
                onDownloadComplete = {
                    navController.navigate(AppRoutes.INTERPRET) {
                        popUpTo(AppRoutes.MODEL_DOWNLOAD) { inclusive = true }
                    }
                }
            )
        }

        composable(AppRoutes.INTERPRET) {
            InterpretScreen(
                modelManager = modelManager,
                onNavigateToProfile = {
                    navController.navigate(AppRoutes.VOICE_PROFILE)
                },
                onNavigateToSettings = {
                    navController.navigate(AppRoutes.SETTINGS)
                }
            )
        }

        composable(AppRoutes.VOICE_PROFILE) {
            VoiceProfileScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(AppRoutes.SETTINGS) {
            SettingsScreen(
                modelManager = modelManager,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
