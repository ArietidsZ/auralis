package com.dialect.interpreter.ui.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.dialect.interpreter.ui.screens.InterpretScreen
import com.dialect.interpreter.ui.screens.SetupScreen
import com.dialect.interpreter.ui.screens.SettingsScreen
import com.dialect.interpreter.ui.screens.VoiceProfileScreen

/**
 * App navigation graph. The app always starts on the interpret screen —
 * missing/draft models are surfaced there as an install gate (spec U02: setup
 * must be leaveable at any time, never a trap).
 */
object AppRoutes {
    const val INTERPRET = "interpret"
    const val SETUP = "setup"
    const val VOICE_PROFILE = "voice_profile"
    const val SETTINGS = "settings"
}

private const val TRANSITION_MS = 220

@Composable
fun AppNavigation(reduceMotion: Boolean = false) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = AppRoutes.INTERPRET,
        enterTransition = {
            if (reduceMotion) fadeIn(tween(1)) else slideInHorizontally(
                initialOffsetX = { it / 4 },
                animationSpec = tween(TRANSITION_MS)
            ) + fadeIn(tween(TRANSITION_MS))
        },
        exitTransition = {
            if (reduceMotion) fadeOut(tween(1)) else slideOutHorizontally(
                targetOffsetX = { -it / 4 },
                animationSpec = tween(TRANSITION_MS)
            ) + fadeOut(tween(TRANSITION_MS / 2))
        },
        popEnterTransition = {
            if (reduceMotion) fadeIn(tween(1)) else slideInHorizontally(
                initialOffsetX = { -it / 4 },
                animationSpec = tween(TRANSITION_MS)
            ) + fadeIn(tween(TRANSITION_MS))
        },
        popExitTransition = {
            if (reduceMotion) fadeOut(tween(1)) else slideOutHorizontally(
                targetOffsetX = { it / 4 },
                animationSpec = tween(TRANSITION_MS)
            ) + fadeOut(tween(TRANSITION_MS / 2))
        },
    ) {
        composable(AppRoutes.INTERPRET) {
            InterpretScreen(
                reduceMotion = reduceMotion,
                onNavigateToSetup = { navController.navigate(AppRoutes.SETUP) },
                onNavigateToProfile = { navController.navigate(AppRoutes.VOICE_PROFILE) },
                onNavigateToSettings = { navController.navigate(AppRoutes.SETTINGS) },
            )
        }

        composable(AppRoutes.SETUP) {
            SetupScreen(onBack = { navController.popBackStack() })
        }

        composable(AppRoutes.VOICE_PROFILE) {
            VoiceProfileScreen(onBack = { navController.popBackStack() })
        }

        composable(AppRoutes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onNavigateToSetup = {
                    navController.navigate(AppRoutes.SETUP) {
                        popUpTo(AppRoutes.INTERPRET) { saveState = true }
                    }
                },
            )
        }
    }
}
