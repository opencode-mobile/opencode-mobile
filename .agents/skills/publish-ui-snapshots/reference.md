# Throwaway snapshot templates

Do not commit these files. Write them into the gitignored paths in SKILL.md.

## iOS — assistant message

`ios/OpenCode Mobile/OpenCode MobileTests/GeneratedSnapshotEvidence/AssistantMessageSnapshotEvidence.swift`

```swift
import SwiftUI
import Testing
@testable import OpenCode_Mobile

@MainActor
@Suite("AssistantMessageSnapshotEvidence")
struct AssistantMessageSnapshotEvidence {
    @Test func isolatedLight() throws {
        try SnapshotEvidenceRenderer.writePNG(
            name: "ios-isolated-light",
            appearance: .light
        ) {
            OpenCodeAssistantMessage(text: SnapshotEvidenceMocks.assistantReply.text)
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(OpenCodeColors.backgroundBase)
        }
    }

    @Test func inSessionLight() throws {
        try SnapshotEvidenceRenderer.writePNG(
            name: "ios-in-session-light",
            appearance: .light,
            height: SnapshotEvidenceRenderer.phoneHeight
        ) {
            SessionChatSnapshotHost(
                sessionTitle: SnapshotEvidenceMocks.sessionTitle,
                messages: SnapshotEvidenceMocks.conversation,
                composerState: SnapshotEvidenceMocks.idleComposer
            )
        }
    }
}
```

For dark, duplicate with `appearance: .dark` and names `ios-isolated-dark` / `ios-in-session-dark`.

## Android — text input

`:screenshot` compiles theme, icons, and a small set of field-chrome components (see
`android/screenshot/src/main/java/.../ui/components/` symlinks). Add a symlink
there when a throwaway test needs another Hilt-free component.

`android/screenshot/src/test/java/dev/opencode/mobile/ui/snapshot/GeneratedSnapshotEvidence/TextInputSnapshotEvidence.kt`

```kotlin
package dev.opencode.mobile.ui.snapshot.GeneratedSnapshotEvidence

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.ui.components.OpenCodeTextInput
import dev.opencode.mobile.ui.components.OpenCodeTextInputKind
import org.junit.Rule
import org.junit.Test

class TextInputSnapshotEvidence {
    @get:Rule
    val paparazzi =
        Paparazzi(
            deviceConfig = DeviceConfig.PIXEL_5,
            theme = "android:Theme.Material.Light.NoActionBar",
            showSystemUi = false,
        )

    @Test
    fun isolatedLight() {
        paparazzi.snapshot("android-isolated-light") {
            OpenCodeTheme(darkTheme = false) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(20.dp),
                ) {
                    OpenCodeTextInput(
                        value = "",
                        onValueChange = {},
                        hint = "Server URL",
                        kind = OpenCodeTextInputKind.Url,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    @Test
    fun isolatedDark() {
        paparazzi.unsafeUpdateConfig(
            deviceConfig = DeviceConfig.PIXEL_5.copy(nightMode = NightMode.NIGHT),
        )
        paparazzi.snapshot("android-isolated-dark") {
            OpenCodeTheme(darkTheme = true) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(20.dp),
                ) {
                    OpenCodeTextInput(
                        value = "",
                        onValueChange = {},
                        hint = "Server URL",
                        kind = OpenCodeTextInputKind.Url,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
```

Copy recorded files whose names end in `_android-isolated-light.png` (etc.) to
`.snapshot-evidence/out/android-isolated-light.png`. Never run
`verifyPaparazziDebug`.

## Other subjects

Add committed mocks next to `SnapshotEvidenceMocks` (same copy on both platforms)
when the subject lives in Session Chat. Isolated view must be the subject chrome.
On Android, only Hilt-free components that `:screenshot` compiles are available
until a new symlink is added.
