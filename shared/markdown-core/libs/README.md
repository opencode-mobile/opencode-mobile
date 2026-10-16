# Patched `markdown-jvm` (Android 15 List API)

Google Play rejects AABs that call Java 21 `List.removeFirst()` / `removeLast()`
because those methods crash on Android 14 and below.

`org.jetbrains:markdown` 0.7.8 shipped
`org.intellij.markdown.lexer.GeneratedLexerKt.pop` as `ArrayList.removeLast()`.
0.7.16 no longer does. Its remaining `removeLast` calls are
`kotlin.collections.ArrayDeque.removeLast`, which is Kotlin stdlib, not
`java.util.List.removeLast`.

`android/settings.gradle.kts` resolves `org.jetbrains:markdown-jvm` only from
`repo/`. The pinned version has to be published there even when the bytecode
needs no rewrite. `repo/` currently provides `markdown-jvm:0.7.16`, byte-for-byte
the Maven Central jar.

## Regenerate

```bash
shared/markdown-core/libs/regenerate-patched-markdown-jvm.sh 0.7.16
# Patched classes: 0 means the jar has no java.util.List removeFirst/removeLast.
# Still commit repo/org/jetbrains/markdown-jvm/<version>/ so Gradle can resolve it.
```

Keep the version pin in `android/gradle/libs.versions.toml` in sync with this
directory when bumping.
