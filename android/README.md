# OpenCode Mobile (Android)

Kotlin + Jetpack Compose app. Kotlin sources use **4-space** indentation (see repo-root [`.editorconfig`](../.editorconfig)).

## Code style

Formatting and style checks use [ktlint](https://github.com/pinterest/ktlint) via the Gradle plugin:

```bash
./gradlew ktlintFormat   # auto-fix style (including indentation)
./gradlew ktlintCheck    # fail on violations — run before opening a PR
```

Generated [`OpenCodeIcons.kt`](app/src/main/java/dev/opencode/mobile/ui/icons/OpenCodeIcons.kt) is excluded from ktlint; regenerate with `npm run sync-icons` from the repo root.

When CI is added for Android, include `ktlintCheck` in the job.

## Build

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

Throwaway UI snapshot evidence uses Paparazzi on `:screenshot` (Java 21 Gradle JVM), not `:app`. The module is included only on Java 21, or when you request a `:screenshot` / Paparazzi task (`-PincludeScreenshot=true` forces it):

```bash
./gradlew :screenshot:recordPaparazziDebug
```
