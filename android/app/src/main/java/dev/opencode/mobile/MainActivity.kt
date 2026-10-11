package dev.opencode.mobile

import android.os.Bundle
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dagger.hilt.android.AndroidEntryPoint
import dev.opencode.mobile.domain.event.WorldSignals
import dev.opencode.mobile.ui.components.LocalOpenCodeInputFocusRegistry
import dev.opencode.mobile.ui.keyboard.KeyboardShortcutHost
import dev.opencode.mobile.ui.keyboard.LocalKeyboardShortcutHost
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var worldSignals: WorldSignals

    private val keyboardShortcuts = KeyboardShortcutHost()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        worldSignals.register()

        enableEdgeToEdge()
        setContent {
            CompositionLocalProvider(
                LocalKeyboardShortcutHost provides keyboardShortcuts,
                LocalOpenCodeInputFocusRegistry provides keyboardShortcuts.inputFocus,
            ) {
                MainNavigation()
            }
        }
    }

    // Runs before the view hierarchy, so Compose focus traversal cannot take Ctrl+Tab first.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (keyboardShortcuts.handleKeyEvent(event, currentFocus)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onProvideKeyboardShortcuts(
        data: MutableList<KeyboardShortcutGroup>?,
        menu: Menu?,
        deviceId: Int,
    ) {
        super.onProvideKeyboardShortcuts(data, menu, deviceId)
        keyboardShortcuts.shortcutGroup()?.let { data?.add(it) }
    }
}
