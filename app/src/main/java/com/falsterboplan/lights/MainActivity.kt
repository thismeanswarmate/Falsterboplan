package com.falsterboplan.lights

import android.graphics.Color
import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.falsterboplan.lights.ui.HomeScreen
import com.falsterboplan.lights.ui.HomeTheme
import com.falsterboplan.lights.ui.MainViewModel
import com.falsterboplan.lights.ui.ShoppingViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val shopping: ShoppingViewModel by viewModels()

    /** Tab the widget asked to open, if any. */
    private var requestedTab by mutableStateOf<Int?>(null)

    private fun readTab(intent: Intent?) {
        intent?.getIntExtra(EXTRA_TAB, -1)?.takeIf { it >= 0 }?.let { requestedTab = it }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readTab(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) readTab(intent)
        // The app is always cream, so system bar icons must stay dark even when the phone is in dark mode.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        setContent {
            HomeTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                HomeScreen(state, viewModel, shopping, requestedTab, onTabShown = { requestedTab = null })
            }
        }
    }

    companion object {
        const val EXTRA_TAB = "tab"
    }
}
