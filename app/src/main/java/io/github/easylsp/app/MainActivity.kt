package io.github.easylsp.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.easylsp.app.ui.EasylspNavigation
import io.github.easylsp.app.ui.theme.EasylspTheme

class MainActivity : ComponentActivity() {
    private val destination = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent); setIntent(intent); destination.value = intent.getStringExtra("route") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        destination.value = intent.getStringExtra("route")
        enableEdgeToEdge()
        val graph = application as EasylspApp
        setContent {
            val appearance by graph.settings.appearance.collectAsStateWithLifecycle("system")
            val monet by graph.settings.monet.collectAsStateWithLifecycle(false)
            val scale by graph.settings.pageScale.collectAsStateWithLifecycle(1f)
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density * scale, density.fontScale)) {
                EasylspTheme(appearance, monet) { EasylspNavigation(destination = destination, consumed = { destination.value = null }) }
            }
        }
    }
}
