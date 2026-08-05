package dev.opencode.mobile.theme

import androidx.compose.ui.graphics.Color

object ThemeResolver {
    fun resolve(
        variant: ThemeVariant,
        isDark: Boolean,
    ): ResolvedTokens {
        val p = variant.palette
        val overrides = variant.overrides ?: emptyMap()
        val neutral = p.neutral.normalizedHex()
        val ink = p.ink.normalizedHex()
        val primary = p.primary.normalizedHex()
        val interactive = (p.interactive ?: p.primary).normalizedHex()
        val success = p.success.normalizedHex()
        val warning = p.warning.normalizedHex()
        val error = p.error.normalizedHex()
        val info = p.info.normalizedHex()
        val diffAdd = (p.diffAdd ?: p.success).normalizedHex()
        val diffDelete = (p.diffDelete ?: p.error).normalizedHex()

        val tokens = mutableMapOf<String, String>()

        // Background / surfaces
        tokens["background-base"] = surfaceBase(neutral, isDark)
        tokens["surface-base"] = surfaceBase(neutral, isDark)
        tokens["surface-base-hover"] = if (isDark) "#FFFFFF0D" else "#0000000A"
        tokens["surface-raised-base"] = surfaceRaisedBase(neutral, isDark)
        tokens["surface-raised-base-hover"] = surfaceRaisedBaseHover(neutral, isDark)
        tokens["surface-interactive-weak"] = surfaceInteractiveWeak(interactive, neutral, isDark)
        tokens["surface-success-base"] = surfaceStatusBase(success, neutral, isDark)
        tokens["surface-critical-base"] = surfaceStatusBase(error, neutral, isDark)

        // Text
        tokens["text-strong"] = textStrong(ink, isDark)
        tokens["text-base"] = mix(ink, neutral, if (isDark) 0.42f else 0.45f)
        tokens["text-weak"] = mix(ink, neutral, if (isDark) 0.58f else 0.62f)
        tokens["text-weaker"] = mix(ink, neutral, if (isDark) 0.72f else 0.78f)
        tokens["text-interactive-base"] = interactive
        tokens["text-on-brand-base"] = readableText(primary)

        // Borders
        tokens["border-weak-base"] = mix(neutral, ink, if (isDark) 0.06f else 0.12f)
        tokens["border-weaker-base"] = mix(neutral, ink, if (isDark) 0.03f else 0.07f)

        // Icons
        tokens["icon-base"] = mix(ink, neutral, if (isDark) 0.55f else 0.62f)
        tokens["icon-weak-base"] = mix(ink, neutral, if (isDark) 0.78f else 0.82f)
        tokens["icon-strong-base"] = textStrong(ink, isDark)
        tokens["icon-invert-base"] = if (isDark) "#1C1C1C" else "#FFFFFF"

        // Buttons
        tokens["button-primary-base"] = textStrong(ink, isDark)
        tokens["button-secondary-base"] = if (isDark) surfaceBase(neutral, isDark) else "#FCFCFC"
        tokens["button-secondary-hover"] = surfaceRaisedBaseHover(neutral, isDark)

        // Status seeds
        tokens["interactive"] = interactive
        tokens["success"] = success
        tokens["warning"] = warning
        tokens["error"] = error
        tokens["info"] = info
        tokens["diff-add"] = diffAdd
        tokens["diff-delete"] = diffDelete

        // Syntax
        tokens["syntax-comment"] = mix(ink, neutral, 0.55f)
        tokens["syntax-keyword"] = info
        tokens["syntax-string"] = "#00CEB9"
        tokens["syntax-primitive"] = if (isDark) mix(interactive, "#FFFFFF", 0.42f) else interactive
        tokens["syntax-property"] = if (isDark) primary else info
        tokens["syntax-type"] = warning
        tokens["syntax-success"] = success
        tokens["syntax-warning"] = warning
        tokens["syntax-info"] = info
        tokens["syntax-constant"] = if (isDark) "#93E9F6" else "#007B80"
        tokens["syntax-variable"] = if (isDark) "#E06C75" else "#D1383D"
        tokens["syntax-operator"] = if (isDark) "#56B6C2" else "#318795"
        tokens["syntax-punctuation"] = textStrong(ink, isDark)
        tokens["syntax-critical"] = if (isDark) primary else "#FF8C00"
        tokens["syntax-diff-delete"] = if (isDark) primary else "#FF8C00"
        tokens["syntax-diff-unknown"] = info

        // Markdown
        tokens["markdown-h1"] = textStrong(ink, isDark)
        tokens["markdown-h2"] = textStrong(ink, isDark)
        tokens["markdown-h3"] = textStrong(ink, isDark)
        // Links use body-strong + bold/underline chrome, not interactive accent.
        tokens["markdown-link-text"] = textStrong(ink, isDark)
        tokens["markdown-link-underline"] = textStrong(ink, isDark)
        tokens["markdown-code-text"] = textStrong(ink, isDark)
        // Match desktop session-ui: color-mix(text, 8%, transparent). Native
        // attributed backgrounds have no CSS padding, so keep the same 8% tint.
        tokens["markdown-code-bg"] = if (isDark) "#FFFFFF14" else "#00000014"
        tokens["markdown-quote-text"] = mix(ink, neutral, if (isDark) 0.42f else 0.45f)
        // Quote bar matches desktop muted border, not interactive accent.
        tokens["markdown-quote-bar"] = tokens.getValue("border-weak-base")
        tokens["markdown-list-marker"] = mix(ink, neutral, if (isDark) 0.42f else 0.45f)
        tokens["markdown-image"] = mix(ink, neutral, if (isDark) 0.58f else 0.62f)
        tokens["markdown-hr"] = mix(neutral, ink, if (isDark) 0.03f else 0.07f)
        tokens["markdown-strong"] = textStrong(ink, isDark)
        tokens["markdown-em"] = mix(ink, neutral, if (isDark) 0.42f else 0.45f)

        // Apply overrides
        for ((key, value) in overrides) {
            tokens[key] = value.normalizedHex()
        }

        return ResolvedTokens(tokens)
    }

    // --- Hex utilities ---

    private fun String.normalizedHex(): String {
        val trimmed = trim()
        return if (trimmed.startsWith("#")) trimmed else "#$trimmed"
    }

    private data class Rgb(
        val r: Float,
        val g: Float,
        val b: Float,
    )

    private fun hexToRgb(hex: String): Rgb {
        val clean = hex.normalizedHex().removePrefix("#").take(6)
        if (clean.length != 6) return Rgb(128f, 128f, 128f)
        val value = clean.toInt(16)
        return Rgb(
            ((value shr 16) and 0xFF).toFloat(),
            ((value shr 8) and 0xFF).toFloat(),
            (value and 0xFF).toFloat(),
        )
    }

    private fun rgbToHex(
        r: Float,
        g: Float,
        b: Float,
    ): String {
        val clamp: (Float) -> Int = { (it.coerceIn(0f, 255f)).toInt() }
        return "#${String.format("%02X%02X%02X", clamp(r), clamp(g), clamp(b))}"
    }

    private fun mix(
        a: String,
        b: String,
        amount: Float,
    ): String {
        val (r1, g1, b1) = hexToRgb(a)
        val (r2, g2, b2) = hexToRgb(b)
        return rgbToHex(
            r1 + (r2 - r1) * amount,
            g1 + (g2 - g1) * amount,
            b1 + (b2 - b1) * amount,
        )
    }

    private fun readableText(fill: String): String {
        val (r, g, b) = hexToRgb(fill)
        val luminance = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
        return if (luminance > 0.55f) "#171311" else "#FFFFFF"
    }

    // --- Generation helpers ---

    private fun textStrong(
        ink: String,
        isDark: Boolean,
    ): String = mix(ink, if (isDark) "#FFFFFF" else "#000000", if (isDark) 0.04f else 0.02f)

    private fun surfaceBase(
        neutral: String,
        isDark: Boolean,
    ): String = mix(neutral, if (isDark) "#000000" else "#FFFFFF", if (isDark) 0.10f else 0.22f)

    private fun surfaceRaisedBase(
        neutral: String,
        isDark: Boolean,
    ): String = mix(neutral, if (isDark) "#FFFFFF" else "#000000", if (isDark) 0.02f else 0.03f)

    private fun surfaceRaisedBaseHover(
        neutral: String,
        isDark: Boolean,
    ): String = mix(neutral, if (isDark) "#FFFFFF" else "#000000", if (isDark) 0.05f else 0.07f)

    private fun surfaceInteractiveWeak(
        interactive: String,
        neutral: String,
        isDark: Boolean,
    ): String = mix(interactive, if (isDark) neutral else "#FFFFFF", if (isDark) 0.78f else 0.94f)

    private fun surfaceStatusBase(
        color: String,
        neutral: String,
        isDark: Boolean,
    ): String = mix(color, if (isDark) neutral else "#FFFFFF", if (isDark) 0.82f else 0.86f)

    // --- Public hex-to-Compose-Color utilities ---

    fun parseHexColor(hex: String): Color {
        val h = hex.trim().removePrefix("#")
        return when (h.length) {
            6 -> Color(("FF$h").toLong(16))
            8 -> {
                val r = h.substring(0, 2)
                val g = h.substring(2, 4)
                val b = h.substring(4, 6)
                val a = h.substring(6, 8)
                Color(("${a}${r}${g}$b").toLong(16))
            }
            else -> Color.Black
        }
    }
}
