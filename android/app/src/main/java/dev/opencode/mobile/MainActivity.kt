package dev.opencode.mobile

import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.AndroidEntryPoint
import dev.opencode.mobile.domain.event.WorldSignals
import dev.opencode.mobile.domain.preferences.AppLockPreferences
import dev.opencode.mobile.domain.security.AppLockAvailability
import dev.opencode.mobile.domain.security.AppLockPolicy
import dev.opencode.mobile.ui.components.LocalOpenCodeInputFocusRegistry
import dev.opencode.mobile.ui.keyboard.KeyboardShortcutHost
import dev.opencode.mobile.ui.keyboard.LocalKeyboardShortcutHost
import dev.opencode.mobile.ui.root.PrivacyCover
import dev.opencode.mobile.ui.root.PrivacyCoverState
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    @Inject lateinit var worldSignals: WorldSignals

    private val keyboardShortcuts = KeyboardShortcutHost()

    // Privacy cover and optional app lock (#755). The cover goes up in onPause so the recents
    // snapshot never shows a transcript; the lock engages on the first resume and after onStop.
    private val privacyCover = mutableStateOf(PrivacyCoverState())
    private var wasInBackground = true
    private var unlockPrompt: BiometricPrompt? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // Rotation, theme, or locale recreation is not a return from the background.
        wasInBackground = savedInstanceState?.getBoolean(STATE_UNLOCKED_ACROSS_RECREATE) != true
        worldSignals.register()

        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Recents shows the launcher icon instead of a live snapshot of the chat.
            setRecentsScreenshotEnabled(false)
        }
        setContent {
            CompositionLocalProvider(
                LocalKeyboardShortcutHost provides keyboardShortcuts,
                LocalOpenCodeInputFocusRegistry provides keyboardShortcuts.inputFocus,
            ) {
                val cover = privacyCover.value
                Box {
                    // Hidden from accessibility services while covered, like it is from touch.
                    Box(if (cover.covered || cover.locked) Modifier.clearAndSetSemantics {} else Modifier) {
                        MainNavigation()
                    }
                    PrivacyCover(state = cover, onUnlock = ::promptUnlock)
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
            promptUnlock()
        } else if (!privacyCover.value.locked) {
            privacyCover.value = PrivacyCoverState()
        }
    }

    override fun onPause() {
        // Only cover here. On API 29 and below the device-credential screen is a separate
        // activity that pauses this one, so cancelling the prompt here would end the unlock.
        privacyCover.value = privacyCover.value.copy(covered = true)
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
        if (!privacyCover.value.locked && keyboardShortcuts.handleKeyEvent(event, currentFocus)) return true
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
