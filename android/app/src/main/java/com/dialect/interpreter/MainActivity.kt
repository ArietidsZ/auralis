package com.dialect.interpreter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dialect.interpreter.ui.navigation.AppNavigation
import com.dialect.interpreter.ui.theme.DialectInterpreterTheme

/**
 * Single Activity host (spec 03 U01). Thin on purpose:
 *  - microphone permission is requested from the interpret screen when the user
 *    presses record, never at startup (spec 03 U02);
 *  - no session/runtime teardown here — engine handles are session-owned and
 *    released through SessionController.close() (spec 02 R01);
 *  - the system animator scale ("reduce motion") is re-read on every resume,
 *    so changing it in system settings applies when the user returns without
 *    recreating the activity.
 */
class MainActivity : ComponentActivity() {
    private var reduceMotion by mutableStateOf(false)

    private fun readReducedMotion(): Boolean = android.provider.Settings.Global.getFloat(
        contentResolver,
        android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    ) == 0f

    override fun onResume() {
        super.onResume()
        reduceMotion = readReducedMotion()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        reduceMotion = readReducedMotion()

        setContent {
            DialectInterpreterTheme {
                AppNavigation(reduceMotion = reduceMotion)
            }
        }
    }
}
