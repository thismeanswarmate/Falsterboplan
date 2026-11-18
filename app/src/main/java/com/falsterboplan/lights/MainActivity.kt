package com.falsterboplan.lights

import android.graphics.Color
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import java.util.Locale
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
import com.falsterboplan.lights.presence.Presence
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.glance.appwidget.updateAll
import com.falsterboplan.lights.auth.Auth
import com.falsterboplan.lights.ui.HomeScreen
import com.falsterboplan.lights.ui.LoginScreen
import com.falsterboplan.lights.widget.LightsWidget
import kotlinx.coroutines.launch
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

    /** The app is Swedish throughout, including the wording built into buttons and pickers. */
    override fun attachBaseContext(base: Context) {
        val config = Configuration(base.resources.configuration)
        config.setLocale(Locale("sv", "SE"))
        super.attachBaseContext(base.createConfigurationContext(config))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readTab(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) readTab(intent)
        // Re-arm the home/away checks; the Wi-Fi watch is dropped when the app is updated.
        Presence.apply(this)
        // The app is always cream, so system bar icons must stay dark even when the phone is in dark mode.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        setContent {
            HomeTheme {
                var allowed by remember { mutableStateOf(Auth.allowed(this)) }
                val scope = rememberCoroutineScope()
                if (!allowed) {
                    LoginScreen(onSignedIn = {
                        allowed = true
                        scope.launch { LightsWidget().updateAll(applicationContext) }
                    })
                } else {
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    HomeScreen(
                        state, viewModel, shopping, requestedTab,
                        onTabShown = { requestedTab = null },
                        user = Auth.userName(this),
                        onSignOut = {
                            Auth.signOut(this)
                            allowed = Auth.allowed(this)
                            scope.launch { LightsWidget().updateAll(applicationContext) }
                        },
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_TAB = "tab"
    }
}
