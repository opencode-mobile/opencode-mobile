package dev.opencode.mobile

import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.opencode.mobile.domain.event.WorldSignals
import dev.opencode.mobile.domain.preferences.AppLockPreferences
import dev.opencode.mobile.domain.security.AppLockAvailability
import dev.opencode.mobile.domain.security.AppLockPolicy
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.ui.components.LocalOpenCodeInputFocusRegistry
import dev.opencode.mobile.ui.diagnostic.PerformanceOverlayHost
import dev.opencode.mobile.ui.keyboard.KeyboardShortcutHost
import dev.opencode.mobile.ui.keyboard.LocalKeyboardShortcutHost
import dev.opencode.mobile.ui.root.PrivacyCover
import dev.opencode.mobile.ui.root.PrivacyCoverState
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    @Inject lateinit var worldSignals: WorldSignals

    private val keyboardShortcuts = KeyboardShortcutHost()

    // Privacy cover and optional app lock (#755). The cover goes up in onPause when App Lock
    // is on (recents thumbnails are handled in onCreate); the lock engages on the first
    // resume and after onStop. When App Lock is off the task manager keeps the normal screen.
    private val privacyCover = mutableStateOf(PrivacyCoverState())
    private var wasInBackground = true
    private var unlockPrompt: BiometricPrompt? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // Rotation, theme, or locale recreation is not a return from the background.
        wasInBackground = savedInstanceState?.getBoolean(STATE_UNLOCKED_ACROSS_RECREATE) != true
        worldSignals.register()
        // `--es labRun <plan>` in Lab builds: run the benchmark unattended, then finish.
        if (HighlightingLabSeam.startHeadlessRun(this, intent)) return

        enableEdgeToEdge()
        // The task-manager thumbnail follows the App Lock setting: when on, recents shows
        // a blank/icon instead of live chat; when off, the normal screen stays visible.
        lifecycleScope.launch {
            snapshotFlow { AppLockPreferences.enabled }.collect { enabled ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // Recents shows the launcher icon instead of a live snapshot of the chat.
                    setRecentsScreenshotEnabled(!enabled)
                } else {
                    // Below API 33 the recents thumbnail is taken before onPause, so the cover can never
                    // be in it. With App Lock on, a secure window gives a blank thumbnail instead (and,
                    // as a side effect, blocks screenshots of the app).
                    if (enabled) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                }
            }
        }
        setContent {
            CompositionLocalProvider(
                LocalKeyboardShortcutHost provides keyboardShortcuts,
                LocalOpenCodeInputFocusRegistry provides keyboardShortcuts.inputFocus,
            ) {
                val cover = privacyCover.value
                Box {
                    // Hidden from accessibility services while covered, like it is from touch.
                    Box(
                        if (cover.covered || cover.locked) {
                            Modifier.fillMaxSize().clearAndSetSemantics {}
                        } else {
                            Modifier.fillMaxSize()
                        },
                    ) {
                        MainNavigation()
                        PerformanceOverlayHost()
                    }
                    // MainNavigation applies the theme inside itself; the cover sits beside it.
                    OpenCodeTheme {
                        PrivacyCover(state = cover, onUnlock = ::promptUnlock)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val locked = AppLockPolicy.shouldLock(enabled = AppLockPreferences.enabled, wasInBackground = wasInBackground)
        wasInBackground = false
        if (locked) {
            privacyCover.value = PrivacyCoverState(covered = true, locked = true)
            // Also hides an IME a View-based field opened; the cover takes Compose focus itself.
            WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.ime())
            promptUnlock()
        } else if (!privacyCover.value.locked) {
            privacyCover.value = PrivacyCoverState()
        }
    }

    override fun onPause() {
        // Only cover here when App Lock is on (or already locked). When off, the task
        // manager keeps showing the normal screen. On API 29 and below the device-credential
        // screen is a separate activity that pauses this one, so cancelling the prompt here
        // would end the unlock.
        if (AppLockPreferences.enabled || privacyCover.value.locked) {
            privacyCover.value = privacyCover.value.copy(covered = true)
        }
        super.onPause()
    }

    override fun onStop() {
        if (!isChangingConfigurations) {
            // From API 30 the credential UI stays inside the system prompt, so a stop means the
            // app left the foreground. Below that, BiometricPrompt manages its own lifecycle and
            // a stop during the prompt is the PIN screen, not leaving the app.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                unlockPrompt?.cancelAuthentication()
                unlockPrompt = null
            }
            if (unlockPrompt == null) wasInBackground = true
        }
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(
            STATE_UNLOCKED_ACROSS_RECREATE,
            isChangingConfigurations && !wasInBackground && !privacyCover.value.locked,
        )
    }

    private fun promptUnlock() {
        if (!privacyCover.value.locked || unlockPrompt != null) return
        if (AppLockAvailability.cannotEverUnlock(this)) {
            // The screen lock was removed after App Lock was turned on, so no prompt can pass.
            // Turn App Lock off instead of locking the user out with no way back to Settings.
            AppLockPreferences.updateEnabled(false)
            privacyCover.value = PrivacyCoverState()
            return
        }
        val prompt =
            BiometricPrompt(
                this,
                ContextCompat.getMainExecutor(this),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        unlockPrompt = null
                        privacyCover.value = PrivacyCoverState()
                    }

                    override fun onAuthenticationError(
                        errorCode: Int,
                        errString: CharSequence,
                    ) {
                        // Cancelled or failed: stay covered; the Unlock button prompts again.
                        unlockPrompt = null
                    }
                },
            )
        unlockPrompt = prompt
        prompt.authenticate(
            BiometricPrompt.PromptInfo
                .Builder()
                .setTitle("Unlock OpenCode Mobile")
                .setAllowedAuthenticators(AppLockAvailability.ALLOWED_AUTHENTICATORS)
                .build(),
        )
    }

    // Runs before the view hierarchy, so Compose focus traversal cannot take Ctrl+Tab first.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (privacyCover.value.locked) {
            // Nothing under the lock cover may receive keys (Tab would move focus into it).
            // Confirm keys unlock; Back and volume keep their system behaviour.
            return when (event.keyCode) {
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_SPACE,
                KeyEvent.KEYCODE_DPAD_CENTER,
                -> {
                    if (event.action == KeyEvent.ACTION_UP) promptUnlock()
                    true
                }
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_VOLUME_UP,
                KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent.KEYCODE_VOLUME_MUTE,
                -> super.dispatchKeyEvent(event)
                else -> true
            }
        }
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

    private companion object {
        const val STATE_UNLOCKED_ACROSS_RECREATE = "appLock.unlockedAcrossRecreate"
    }
}
