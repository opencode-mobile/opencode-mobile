# Snapshot recipes and templates

Read only the platform branch needed by the snapshot matrix. Keep these files
throwaway. Write them into the gitignored paths below and leave them out of
commits.

## Paths

| Kind | Path |
| --- | --- |
| iOS throwaway tests | `ios/OpenCode Mobile/OpenCode MobileTests/GeneratedSnapshotEvidence/*SnapshotEvidence.swift` |
| Android throwaway tests | `android/screenshot/src/test/java/dev/opencode/mobile/ui/snapshot/GeneratedSnapshotEvidence/*SnapshotEvidence.kt` |
| Android Paparazzi goldens | `android/screenshot/src/test/snapshots/images/` |
| Output | `.snapshot-evidence/out/` |
| iOS helper | `OpenCode MobileTests/SnapshotEvidenceRenderer.swift` |
| Hosts/mocks | iOS `OpenCode Mobile/SnapshotEvidence/`, Android `ui/snapshot/` |

## iOS generation

From `ios/OpenCode Mobile`, after writing the throwaway test:

```bash
OUT="$REPO/.snapshot-evidence/out"
mkdir -p "$OUT"
xcodebuild test \
  -project "OpenCode Mobile.xcodeproj" \
  -scheme "OpenCode Mobile" \
  -destination "platform=iOS Simulator,name=iPhone 16" \
  -only-testing:"OpenCode MobileTests/AssistantMessageSnapshotEvidence" \
  SNAPSHOT_EVIDENCE_OUT="$OUT" \
  CODE_SIGNING_ALLOWED=NO
```

If the simulator name is wrong, use `.github/scripts/select-ios-simulator.sh` /
`xcrun simctl list`. Skip iOS on Linux.

**Completion criterion:** Every requested iOS frame and appearance has a PNG in
`.snapshot-evidence/out/`, or the result names the unavailable simulator,
platform, or failing test.

## Android generation

From `android/`, after writing the throwaway test:

```bash
OUT="$REPO/.snapshot-evidence/out"
mkdir -p "$OUT"
./gradlew :screenshot:recordPaparazziDebug \
  --tests 'dev.opencode.mobile.ui.snapshot.GeneratedSnapshotEvidence.*'
SNAP_DIR="$REPO/android/screenshot/src/test/snapshots/images"
for png in "$SNAP_DIR"/*.png; do
  name="$(basename "$png" | sed -n 's/.*_\(android-[^.]*\)\.png$/\1/p')"
  [ -n "$name" ] || continue
  cp "$png" "$OUT/${name}.png"
done
```

Paparazzi lives on `:screenshot`, not `:app`. Applying it to `:app` breaks AGP 9
+ Hilt artifact transforms. Paparazzi 2 needs a Java 21 Gradle JVM, so
`:screenshot` stays out of the default project graph on JDK 17. Keep
`verifyPaparazzi*` and `:screenshot:testDebugUnitTest` out of Android Checks.
Do not run `verifyPaparazziDebug`.

**Completion criterion:** Every requested Android frame and appearance has a
copied PNG under `.snapshot-evidence/out/`, and no verification task or
`:app` Paparazzi configuration was used.

## iOS, assistant message

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

## Android, text input

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

Copy recorded files whose names end in `_android-isolated-light.png`, and so on,
to `.snapshot-evidence/out/android-isolated-light.png`.

## Other subjects

When the subject lives in Session Chat, add committed mocks next to
`SnapshotEvidenceMocks` with the same copy on both platforms. The isolated view
must be the subject chrome. On Android, only Hilt-free components compiled by
`:screenshot` are available until a new symlink is added.
