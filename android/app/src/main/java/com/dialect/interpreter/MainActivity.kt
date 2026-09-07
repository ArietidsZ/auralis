package com.dialect.interpreter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.dialect.interpreter.ui.navigation.AppNavigation
import com.dialect.interpreter.ui.theme.DialectInterpreterTheme

/**
 * Single Activity host (spec 03 U01). Thin on purpose:
 *  - microphone permission is requested from the interpret screen when the user
 *    presses record, never at startup (spec 03 U02);
 *  - no session/runtime teardown here — engine handles are session-owned and
 *    released through SessionController.close() (spec 02 R01).
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Respect the system animator scale (TalkBack/accessibility: "reduce
        // motion") once per activity; decorative animations read this flag.
        val reduceMotion = android.provider.Settings.Global.getFloat(
            contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f

        setContent {
            DialectInterpreterTheme {
                AppNavigation(reduceMotion = reduceMotion)
            }
        }
    }
}
