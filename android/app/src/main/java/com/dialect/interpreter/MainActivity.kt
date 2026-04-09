package com.dialect.interpreter

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.dialect.interpreter.ui.navigation.AppNavigation
import com.dialect.interpreter.ui.theme.DialectInterpreterTheme

class MainActivity : ComponentActivity() {

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        // Permission result handled, UI will check permission state
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Request mic permission upfront
        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)

        setContent {
            DialectInterpreterTheme {
                AppNavigation()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        (application as DialectApp).modelManager.releaseAll()
    }
}
