# Patched `markdown-jvm` (Android 15 List API)

Google Play rejects AABs that call Java 21 `List.removeFirst()` / `removeLast()`
because those methods crash on Android 14 and below.

`org.jetbrains:markdown` 0.7.8 still ships
`org.intellij.markdown.lexer.GeneratedLexerKt.pop` as `ArrayList.removeLast()`.
Upstream fixed the source (`removeAt(lastIndex)`) after 0.7.8, but no release
includes it yet — and compiling that source on JDK 21 can emit `removeLast`
again.

`repo/` is a local Maven repository providing a bytecode-patched
`org.jetbrains:markdown-jvm:0.7.8` where `pop` uses `remove(size - 1)`.

## Regenerate

```bash
ASM=$(find ~/.gradle/caches -name 'asm-9.8.jar' | head -1)
IN=$(find ~/.gradle/caches -path '*markdown-jvm/0.7.8/*.jar' | head -1)
# Use shared/markdown-core/libs/PatchRemoveLast.java (or rebuild via CI script)
```

Keep the version pin in `android/gradle/libs.versions.toml` in sync with this
directory when bumping.
