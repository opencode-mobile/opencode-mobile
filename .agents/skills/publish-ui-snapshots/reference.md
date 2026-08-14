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
            SnapshotEvidenceAssistantBubble(text: SnapshotEvidenceMocks.assistantReply.text)
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

## Android — assistant message

Not wired on `:app` yet (no Paparazzi / Compose screenshot plugin). Keep this
template for a follow-up; do not write it unless `recordPaparazziDebug` exists.

`android/app/src/test/java/dev/opencode/mobile/ui/snapshot/GeneratedSnapshotEvidence/AssistantMessageSnapshotEvidence.kt`

```kotlin
package dev.opencode.mobile.ui.snapshot.GeneratedSnapshotEvidence

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.ui.snapshot.SessionChatSnapshotHost
import dev.opencode.mobile.ui.snapshot.SnapshotEvidenceAssistantBubble
import dev.opencode.mobile.ui.snapshot.SnapshotEvidenceMocks
import org.junit.Rule
import org.junit.Test

class AssistantMessageSnapshotEvidence {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5,
        theme = "android:Theme.Material.Light.NoActionBar",
        showSystemUi = false,
    )

    @Test
    fun isolatedLight() {
        paparazzi.snapshot("android-isolated-light") {
            OpenCodeTheme(darkTheme = false) {
                SnapshotEvidenceAssistantBubble(
                    text = SnapshotEvidenceMocks.assistantReply.text,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(16.dp),
                )
            }
        }
    }

    @Test
    fun inSessionLight() {
        paparazzi.snapshot("android-in-session-light") {
            OpenCodeTheme(darkTheme = false) {
                SessionChatSnapshotHost(
                    sessionTitle = SnapshotEvidenceMocks.SESSION_TITLE,
                    messages = SnapshotEvidenceMocks.conversation,
                    composerState = SnapshotEvidenceMocks.idleComposer,
                )
            }
        }
    }
}
```

Rename copied PNGs to `android-isolated-light.png` and `android-in-session-light.png`.
For dark, copy `DeviceConfig.PIXEL_5` with `nightMode = NightMode.NIGHT` and wrap with `OpenCodeTheme(darkTheme = true)`.

## Other subjects

Add committed mocks next to `SnapshotEvidenceMocks` (same copy on both platforms). Isolated view must be the subject chrome; in-session must include that chrome in `SessionChatSnapshotHost.messages`.
